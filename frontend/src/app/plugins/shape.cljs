;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.shape
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.record :as crc]
   [app.common.spec :as us]
   [app.common.text :as txt]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.plugins.flex :as flex]
   [app.plugins.grid :as grid]
   [app.plugins.utils :as utils :refer [locate-objects locate-shape proxy->shape array-to-js]]
   [app.util.object :as obj]))

(declare shape-proxy)

(deftype ShapeProxy [$file $page $id]
  Object
  (resize
    [_ width height]
    (st/emit! (udw/update-dimensions [$id] :width width)
              (udw/update-dimensions [$id] :height height)))

  (clone
    [_]
    (let [ret-v (atom nil)]
      (st/emit! (dws/duplicate-shapes #{$id} :change-selection? false :return-ref ret-v))
      (shape-proxy (deref ret-v))))

  (remove
    [_]
    (st/emit! (dwsh/delete-shapes #{$id})))

  ;; Only for frames + groups + booleans
  (getChildren
    [_]
    (apply array (->> (locate-shape $file $page $id)
                      :shapes
                      (map #(shape-proxy $file $page %)))))

  (appendChild
    [_ child]
    (let [child-id (obj/get child "$id")]
      (st/emit! (udw/relocate-shapes #{child-id} $id 0))))

  (insertChild
    [_ index child]
    (let [child-id (obj/get child "$id")]
      (st/emit! (udw/relocate-shapes #{child-id} $id index))))

  ;; Only for frames
  (addFlexLayout
    [_]
    (st/emit! (dwsl/create-layout-from-id $id :flex :from-frame? true :calculate-params? false))
    (grid/grid-layout-proxy $file $page $id))

  (addGridLayout
    [_]
    (st/emit! (dwsl/create-layout-from-id $id :grid :from-frame? true :calculate-params? false))
    (grid/grid-layout-proxy $file $page $id)))

(crc/define-properties!
  ShapeProxy
  {:name js/Symbol.toStringTag
   :get (fn [] (str "ShapeProxy"))})

(defn shape-proxy
  ([id]
   (shape-proxy (:current-file-id @st/state) (:current-page-id @st/state) id))

  ([page-id id]
   (shape-proxy (:current-file-id @st/state) page-id id))

  ([file-id page-id id]
   (assert (uuid? file-id))
   (assert (uuid? page-id))
   (assert (uuid? id))

   (let [data (locate-shape file-id page-id id)]
     (-> (ShapeProxy. file-id page-id id)
         (crc/add-properties!
          {:name "$id" :enumerable false :get (constantly id)}
          {:name "$file" :enumerable false :get (constantly file-id)}
          {:name "$page" :enumerable false :get (constantly page-id)}

          {:name "id"
           :get #(-> % proxy->shape :id str)}

          {:name "type"
           :get #(-> % proxy->shape :type name)}

          {:name "name"
           :get #(-> % proxy->shape :name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :name value)))))}

          {:name "blocked"
           :get #(-> % proxy->shape :blocked boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :blocked value)))))}

          {:name "hidden"
           :get #(-> % proxy->shape :hidden boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :hidden value)))))}

          {:name "proportionLock"
           :get #(-> % proxy->shape :proportion-lock boolean)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :proportion-lock value)))))}

          {:name "constraintsHorizontal"
           :get #(-> % proxy->shape :constraints-h d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/horizontal-constraint-types value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :constraints-h value))))))}

          {:name "constraintsVertical"
           :get #(-> % proxy->shape :constraints-v d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/vertical-constraint-types value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :constraints-v value))))))}

          {:name "borderRadius"
           :get #(-> % proxy->shape :rx)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (us/safe-int? value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :rx value :ry value))))))}

          {:name "borderRadiusTopLeft"
           :get #(-> % proxy->shape :r1)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (us/safe-int? value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :r1 value))))))}

          {:name "borderRadiusTopRight"
           :get #(-> % proxy->shape :r2)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (us/safe-int? value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :r2 value))))))}

          {:name "borderRadiusBottomRight"
           :get #(-> % proxy->shape :r3)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (us/safe-int? value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :r3 value))))))}

          {:name "borderRadiusBottomLeft"
           :get #(-> % proxy->shape :r4)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (us/safe-int? value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :r4 value))))))}

          {:name "opacity"
           :get #(-> % proxy->shape :opacity)
           :set (fn [self value]
                  (let [id (obj/get self "$id")]
                    (when (and (us/safe-number? value) (>= value 0) (<= value 1))
                      (st/emit! (dwc/update-shapes [id] #(assoc % :opacity value))))))}

          {:name "blendMode"
           :get #(-> % proxy->shape :blend-mode (d/nilv :normal) d/name)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (keyword value)]
                    (when (contains? cts/blend-modes value)
                      (st/emit! (dwc/update-shapes [id] #(assoc % :blend-mode value))))))}

          {:name "shadows"
           :get #(-> % proxy->shape :shadow array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(utils/from-js %) value)]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :shadows value)))))}

          {:name "blur"
           :get #(-> % proxy->shape :blur utils/to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (utils/from-js value)]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :blur value)))))}

          {:name "exports"
           :get #(-> % proxy->shape :exports array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(utils/from-js %) value)]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :exports value)))))}

          ;; Geometry properties
          {:name "x"
           :get #(-> % proxy->shape :x)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (st/emit! (udw/update-position id {:x value}))))}

          {:name "y"
           :get #(-> % proxy->shape :y)
           :set
           (fn [self value]
             (let [id (obj/get self "$id")]
               (st/emit! (udw/update-position id {:y value}))))}

          {:name "parentX"
           :get (fn [self]
                  (let [shape (proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)]
                    (- (:x shape) (:x parent))))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   parent-id (-> self proxy->shape :parent-id)
                   parent (locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                   parent-x (:x parent)]
               (st/emit! (udw/update-position id {:x (+ parent-x value)}))))}

          {:name "parentY"
           :get (fn [self]
                  (let [shape (proxy->shape self)
                        parent-id (:parent-id shape)
                        parent (locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                        parent-y (:y parent)]
                    (- (:y shape) parent-y)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   parent-id (-> self proxy->shape :parent-id)
                   parent (locate-shape (obj/get self "$file") (obj/get self "$page") parent-id)
                   parent-y (:y parent)]
               (st/emit! (udw/update-position id {:y (+ parent-y value)}))))}

          {:name "frameX"
           :get (fn [self]
                  (let [shape (proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-x (:x frame)]
                    (- (:x shape) frame-x)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   frame-id (-> self proxy->shape :frame-id)
                   frame (locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                   frame-x (:x frame)]
               (st/emit! (udw/update-position id {:x (+ frame-x value)}))))}

          {:name "frameY"
           :get (fn [self]
                  (let [shape (proxy->shape self)
                        frame-id (:parent-id shape)
                        frame (locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                        frame-y (:y frame)]
                    (- (:y shape) frame-y)))
           :set
           (fn [self value]
             (let [id (obj/get self "$id")
                   frame-id (-> self proxy->shape :frame-id)
                   frame (locate-shape (obj/get self "$file") (obj/get self "$page") frame-id)
                   frame-y (:y frame)]
               (st/emit! (udw/update-position id {:y (+ frame-y value)}))))}

          {:name "width"
           :get #(-> % proxy->shape :width)}

          {:name "height"
           :get #(-> % proxy->shape :height)}

          {:name "flipX"
           :get #(-> % proxy->shape :flip-x)}

          {:name "flipY"
           :get #(-> % proxy->shape :flip-y)}

          ;; Strokes and fills
          {:name "fills"
           :get #(-> % proxy->shape :fills array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(utils/from-js %) value)]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :fills value)))))}

          {:name "strokes"
           :get #(-> % proxy->shape :strokes array-to-js)
           :set (fn [self value]
                  (let [id (obj/get self "$id")
                        value (mapv #(utils/from-js %) value)]
                    (st/emit! (dwc/update-shapes [id] #(assoc % :strokes value)))))}

          {:name "layoutChild"
           :get
           (fn [self]
             (let [file-id (obj/get self "$file")
                   page-id (obj/get self "$page")
                   id (obj/get self "$id")
                   objects (locate-objects file-id page-id)]
               (when (ctl/any-layout-immediate-child-id? objects id)
                 (flex/layout-child-proxy file-id page-id id))))}

          {:name "layoutCell"
           :get
           (fn [self]
             (let [file-id (obj/get self "$file")
                   page-id (obj/get self "$page")
                   id (obj/get self "$id")
                   objects (locate-objects file-id page-id)]
               (when (ctl/grid-layout-immediate-child-id? objects id)
                 (grid/layout-cell-proxy file-id page-id id))))})

         (cond-> (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data))
           (crc/add-properties!
            {:name "children"
             :enumerable false
             :get #(.getChildren ^js %)}))

         (cond-> (not (or (cfh/frame-shape? data) (cfh/group-shape? data) (cfh/svg-raw-shape? data) (cfh/bool-shape? data)))
           (-> (obj/unset! "appendChild")
               (obj/unset! "insertChild")
               (obj/unset! "getChildren")))

         (cond-> (cfh/frame-shape? data)
           (-> (crc/add-properties!
                {:name "grid"
                 :get
                 (fn [self]
                   (let [layout (-> self proxy->shape :layout)
                         file-id (obj/get self "$file")
                         page-id (obj/get self "$page")
                         id (obj/get self "$id")]
                     (when (= :grid layout)
                       (grid/grid-layout-proxy file-id page-id id))))}

                {:name "flex"
                 :get
                 (fn [self]
                   (let [layout (-> self proxy->shape :layout)
                         file-id (obj/get self "$file")
                         page-id (obj/get self "$page")
                         id (obj/get self "$id")]
                     (when (= :flex layout)
                       (flex/flex-layout-proxy file-id page-id id))))}

                {:name "guides"
                 :get #(-> % proxy->shape :grids array-to-js)
                 :set (fn [self value]
                        (let [id (obj/get self "$id")
                              value (mapv #(utils/from-js %) value)]
                          (st/emit! (dwc/update-shapes [id] #(assoc % :grids value)))))}

                {:name "horizontalSizing"
                 :get #(-> % proxy->shape :layout-item-h-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (when (contains? #{:fix :auto} value)
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-h-sizing value})))))}

                {:name "verticalSizing"
                 :get #(-> % proxy->shape :layout-item-v-sizing (d/nilv :fix) d/name)
                 :set
                 (fn [self value]
                   (let [id (obj/get self "$id")
                         value (keyword value)]
                     (when (contains? #{:fix :auto} value)
                       (st/emit! (dwsl/update-layout #{id} {:layout-item-v-sizing value})))))})))

         (cond-> (not (cfh/frame-shape? data))
           (-> (obj/unset! "addGridLayout")
               (obj/unset! "addFlexLayout")))

         (cond-> (cfh/text-shape? data)
           (-> (crc/add-properties!
                {:name "characters"
                 :get #(-> % proxy->shape :content txt/content->text)
                 :set (fn [self value]
                        (let [id (obj/get self "$id")]
                          (st/emit! (dwc/update-shapes [id] #(txt/change-text % value)))))})

               (crc/add-properties!
                {:name "growType"
                 :get #(-> % proxy->shape :grow-type d/name)
                 :set (fn [self value]
                        (let [id (obj/get self "$id")
                              value (keyword value)]
                          (when (contains? #{:auto-width :auto-height :fixed} value)
                            (st/emit! (dwc/update-shapes [id] #(assoc % :grow-type value))))))})))))))