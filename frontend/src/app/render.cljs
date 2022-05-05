;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.render
  "The main entry point for UI part needed by the exporter."
  (:require
   [app.common.logging :as l]
   [app.common.math :as mth]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.main.data.fonts :as df]
   [app.main.render :as render]
   [app.main.repo :as repo]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [beicon.core :as rx]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [garden.core :refer [css]]
   [rumext.alpha :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SETUP
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(l/initialize!)
(l/set-level! :root :warn)
(l/set-level! :app :info)

(declare ^:private render-single-object)
(declare ^:private render-components)
(declare ^:private render-objects)

(l/info :hint "Welcome to penpot (Export)"
        :version (:full @cf/version)
        :public-uri (str cf/public-uri))

(defn- parse-params
  [loc]
  (let [href (unchecked-get loc "href")]
    (some-> href u/uri :query u/query-string->map)))

(defn init-ui
  []
  (when-let [params (parse-params glob/location)]
    (when-let [component (case (:route params)
                           "objects"    (render-objects params)
                           "components" (render-components params)
                           nil)]
      (mf/mount component (dom/get-element "app")))))

(defn ^:export init
  []
  (init-ui))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COMPONENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ---- SINGLE OBJECT

(defn use-resource
  "A general purpose hook for retrieve or subscribe to remote changes
  using the reactive-streams mechanism mechanism.

  It receives a function to execute for retrieve the stream that will
  be used for creating the subscription. The function should be
  stable, so is the responsability of the user of this hook to
  properly memoize it.

  TODO: this should be placed in some generic hooks namespace but his
  right now is pending of refactor and it will be done later."
  [f]
  (let [[state ^js update-state!] (mf/useState {:loaded? false})]
    (mf/with-effect [f]
      (update-state! (fn [prev] (assoc prev :refreshing? true)))
      (let [on-value (fn [data]
                       (update-state! #(-> %
                                           (assoc :refreshing? false)
                                           (assoc :loaded? true)
                                           (merge data))))
            subs     (rx/subscribe (f) on-value)]
        #(rx/dispose! subs)))
    state))

(mf/defc object-svg
  [{:keys [page-id file-id object-id render-embed? render-texts?]}]
  (let [fetch-state (mf/use-fn
                     (mf/deps file-id page-id object-id)
                     (fn []
                       (->> (rx/zip
                             (repo/query! :font-variants {:file-id file-id})
                             (repo/query! :page {:file-id file-id
                                                 :page-id page-id
                                                 :object-id object-id}))
                            (rx/tap (fn [[fonts]]
                                      (when (seq fonts)
                                        (st/emit! (df/fonts-fetched fonts)))))
                            (rx/map (comp :objects second))
                            (rx/map (fn [objects]
                                      (let [objects (render/adapt-objects-for-shape objects object-id)]
                                        {:objects objects
                                         :object object-id}))))))

        {:keys [objects object]} (use-resource fetch-state)]

    ;; Set the globa CSS to assign the page size, needed for PDF
    ;; exportation process.
    (mf/with-effect [object]
      (when object
        (dom/set-page-style!
          {:size (str/concat
                  (mth/ceil (:width object)) "px "
                  (mth/ceil (:height object)) "px")})))

    (when objects
      [:& render/object-svg
       {:objects objects
        :object-id object-id
        :render-embed? render-embed?
        :render-texts? render-texts?}])))

(mf/defc objects-svg
  [{:keys [page-id file-id object-ids render-embed? render-texts?]}]
  (let [fetch-state (mf/use-fn
                     (mf/deps file-id page-id)
                     (fn []
                       (->> (rx/zip
                             (repo/query! :font-variants {:file-id file-id})
                             (repo/query! :page {:file-id file-id
                                                 :page-id page-id}))
                            (rx/tap (fn [[fonts]]
                                      (when (seq fonts)
                                        (st/emit! (df/fonts-fetched fonts)))))
                            (rx/map (comp :objects second)))))

        objects (use-resource fetch-state)]

    (when objects
      (for [object-id object-ids]
        (let [objects (render/adapt-objects-for-shape objects object-id)]
          [:& render/object-svg
           {:objects objects
            :key (str object-id)
            :object-id object-id
            :render-embed? render-embed?
            :render-texts? render-texts?}])))))

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id
  (s/or :single ::us/uuid
        :multiple (s/coll-of ::us/uuid)))
(s/def ::render-text ::us/boolean)
(s/def ::embed ::us/boolean)

(s/def ::render-objects
  (s/keys :req-un [::file-id ::page-id ::object-id]
          :opt-un [::render-text ::render-embed]))

(defn- render-objects
  [params]
  (let [{:keys [file-id
                page-id
                render-embed
                render-texts]
         :as params}
        (us/conform ::render-objects params)

        [type object-id] (:object-id params)]

    (case type
      :single
      (mf/html
       [:& object-svg
        {:file-id file-id
         :page-id page-id
         :object-id object-id
         :render-embed? render-embed
         :render-texts? render-texts}])

      :multiple
      (mf/html
       [:& objects-svg
        {:file-id file-id
         :page-id page-id
         :object-ids (into #{} object-id)
         :render-embed? render-embed
         :render-texts? render-texts}]))))

;; ---- COMPONENTS SPRITE

(mf/defc components-sprite-svg
  [{:keys [file-id embed] :as props}]
  (let [fetch (mf/use-fn
               (mf/deps file-id)
               (fn [] (repo/query! :file {:id file-id})))

        file  (use-resource fetch)
        state (mf/use-state nil)]

    (when file
      [:*
       [:style
        (css [[:body
               {:margin 0
                :overflow "hidden"
                :width "100vw"
                :height "100vh"}]

              [:main
               {:overflow "auto"
                :display "flex"
                :justify-content "center"
                :align-items "center"
                :height "calc(100vh - 200px)"}
               [:svg {:width "50%"
                      :height "50%"}]]
              [:.nav
               {:display "flex"
                :margin 0
                :padding "10px"
                :flex-direction "column"
                :flex-wrap "wrap"
                :height "200px"
                :list-style "none"
                :overflow-x "scroll"
                :border-bottom "1px dotted #e6e6e6"}
               [:a {:cursor :pointer
                    :text-overflow "ellipsis"
                    :white-space "nowrap"
                    :overflow "hidden"
                    :text-decoration "underline"}]
               [:li {:display "flex"
                     :width "150px"
                     :padding "5px"
                     :border "0px solid black"}]]])]

       [:ul.nav
        (for [[id data] (get-in file [:data :components])]
          (let [on-click (fn [event]
                           (dom/prevent-default event)
                           (swap! state assoc :component-id id))]
            [:li {:key (str id)}
             [:a {:on-click on-click} (:name data)]]))]

       [:main
        [:& render/components-sprite-svg
         {:data (:data file)
          :embed embed}

         (when-let [component-id (:component-id @state)]
           [:use {:x 0 :y 0 :xlinkHref (str "#" component-id)}])]]

       ])))

(s/def ::component-id ::us/uuid)
(s/def ::render-components
  (s/keys :req-un [::file-id]
          :opt-un [::embed ::component-id]))

(defn render-components
  [params]
  (let [{:keys [file-id component-id embed]} (us/conform ::render-components params)]
    (mf/html
     [:& components-sprite-svg
      {:file-id file-id
       :component-id component-id
       :embed embed}])))