(ns cantata.data-model
  (:refer-clojure :exclude [resolve])
  (:require [cantata.reflect :as reflect]
            [cantata.records :as r]
            [cantata.util :as cu]
            [flatland.ordered.map :as om]
            [clojure.string :as string])
  (:import [cantata.records Entity Field Rel DataModel]))

(defn make-field [m]
  (r/map->Field
    (if (:db-name m)
      m
      (assoc m :db-name (reflect/guess-db-name (:name m))))))

(defn guess-rel-key [rname]
  (keyword (str (name rname) "-id")))

(defn make-rel [m]
  (r/map->Rel
    (let [name (:name m)
          ename (:ename m)]
      (cond-> m
              (not ename) (assoc :ename name)
              (not (:key m)) (assoc :key (guess-rel-key name))
              (nil? (:reverse m)) (assoc :reverse false)))))

(defn make-shortcut [m]
  (r/map->Shortcut m))

(defn ^:private ordered-map-by-name [maps f]
  (reduce
    #(assoc %1 (:name %2) (f %2))
    (om/ordered-map)
    maps))

(defn make-entity [m]
  (r/map->Entity
    (let [fields (ordered-map-by-name (:fields m) make-field)
          pk (or (:pk m)
                 (key (first fields)))
          rels (ordered-map-by-name (:rels m) make-rel)
          shortcuts (ordered-map-by-name (:shortcuts m) make-shortcut)]
      (cond-> (assoc m
                     :fields fields
                     :rels rels
                     :shortcuts shortcuts)
              (not (:db-name m)) (assoc :db-name (reflect/guess-db-name (:name m)))
              (not (:pk m)) (assoc :pk pk)))))

(defn ^:private reverse-rel-name [rel from]
  (keyword (str "_"
                (name (:name rel)) "."
                (name from))))

(defn ^:private add-reverse-rels [ents ent rel]
  (let [ename (:ename rel)
        from (:name ent)
        rrname (reverse-rel-name rel from)
        rrel (make-rel {:name rrname
                        :ename from
                        :key (:key rel)
                        :other-key (:other-key rel)
                        :reverse true})
        rrname2 from
        rrel2 (assoc rrel :name rrname2)]
    (-> ents
      (assoc-in [ename :rels rrname] rrel)
      (update-in [ename :rels rrname2] #(when-not %1 %2) rrel2))))

(defn data-model [& entity-specs]
  ;; TODO: enforce naming uniqueness, check for bad rels
  (let [ents (ordered-map-by-name entity-specs make-entity)
        ents (reduce
               (fn [ents [ent rel]]
                 (add-reverse-rels ents ent rel))
               ents
               (for [ent (vals ents)
                     rel (vals (:rels ent))]
                 [ent rel]))]
    (r/->DataModel ents)))

(defn data-model? [x]
  (instance? DataModel x))

(defn reflect-data-model [ds & entity-specs]
  (apply
    data-model
    (let [especs (or (not-empty entity-specs)
                     (reflect/reflect-entities ds))]
      (for [espec especs]
        (let [db-name (or (:db-name espec)
                          (reflect/guess-db-name (:name espec)))]
          (assoc espec
                 :fields (or (:fields espec)
                             (reflect/reflect-fields ds db-name))
                 :rels (or (:rels espec)
                           (reflect/reflect-rels ds db-name))
                 :pk (or (:pk espec)
                         (:name (first (:fields espec)))
                         (reflect/reflect-pk ds db-name))))))))

;;;;

(defn entities [dm]
  (vals (:entities dm)))

(defn entity [dm ename]
  (get-in dm [:entities ename]))

(defn entity? [x]
  (instance? Entity x))

(defn fields
  ([ent]
    (vals (:fields ent)))
  ([dm ename]
    (fields (entity dm ename))))

(defn field-names
  ([ent]
    (keys (:fields ent)))
  ([dm ename]
    (field-names (entity dm ename))))

(defn field
  ([ent fname]
    (get-in ent [:fields fname]))
  ([dm ename fname]
    (field (entity dm ename) fname)))

(defn field? [x]
  (instance? Field x))

(defn rels
  ([ent]
    (vals (:rels ent)))
  ([dm ename]
    (rels (entity dm ename))))

(defn rel
  ([ent rname]
    (get-in ent [:rels rname]))
  ([dm ename rname]
    (rel (entity dm ename) rname)))

(defn rel? [x]
  (instance? Rel x))

(defn shortcuts
  ([ent]
    (vals (:shortcuts ent)))
  ([dm ename]
    (shortcuts (entity dm ename))))

(defn shortcut
  ([ent sname]
    (get-in ent [:shortcuts sname]))
  ([dm ename sname]
    (shortcut (entity dm ename) sname)))

(defn normalize-pk [pk]
  (cu/seqify pk))

;;;;

(defn resolve
  ([ent xname]
    (if (identical? xname :*)
      (r/->Resolved :wildcard :*)
      (if-let [f (field ent xname)]
       (r/->Resolved :field f)
       (if-let [r (rel ent xname)]
         (r/->Resolved :rel r)
         (when-let [sc (shortcut ent xname)]
           (r/->Resolved :shortcut sc))))))
  ([dm ename xname]
    (resolve (entity dm ename) xname)))

(defn resolve-path [dm ename-or-entity path]
  (loop [chain []
         ent (if (keyword? ename-or-entity)
               (entity dm ename-or-entity)
               ename-or-entity)
         rnames (cu/split-path path)
         seen-path []
         shortcuts {}]
    (let [rname (first rnames)
          resolved (or (resolve ent rname)
                       (throw (ex-info (str "Unknown reference " rname
                                            " for entity " (:name ent))
                                       {:data-model dm
                                        :rname rname
                                        :entity ent})))]
      (if (and (not (next rnames))
               (not= :shortcut (:type resolved)))
        (if (= :rel (:type resolved))
          (let [rel (:value resolved)
                ent* (entity dm (:ename rel))]
            (r/->ResolvedPath
              (conj chain (r/->ChainLink
                            ent
                            ent*
                            (apply cu/join-path seen-path)
                            (let [joined-seen-path (apply cu/join-path (conj seen-path rname))]
                                (or (shortcuts joined-seen-path)
                                    joined-seen-path))
                            rel))
              (r/->Resolved :entity ent*)
              shortcuts))
          (r/->ResolvedPath chain resolved shortcuts))
        (condp = (:type resolved)
          :shortcut (let [shortcut-path (-> resolved :value :path)]
                      (recur chain
                             ent
                             (concat (cu/split-path shortcut-path)
                                     (rest rnames))
                             seen-path
                             (assoc shortcuts
                                    (apply cu/join-path (conj seen-path shortcut-path))
                                    (apply cu/join-path (conj seen-path
                                                              (-> resolved :value :name))))))
          :rel (if (= :rel (:type resolved))
                 (let [rel (:value resolved)
                       ename* (:ename rel)
                       ent* (entity dm ename*)
                       seen-path* (conj seen-path rname)
                       [seen-path* joined-seen-path] (let [joined-seen-path (apply cu/join-path seen-path*)]
                                                       (if-let [sc (shortcuts joined-seen-path)]
                                                         [(cu/split-path sc) sc]
                                                         [seen-path* joined-seen-path]))
                       link (r/->ChainLink
                              ent
                              ent*
                              (apply cu/join-path seen-path)
                              joined-seen-path
                              rel)]
                   (recur (conj chain link)
                          ent*
                          (rest rnames)
                          seen-path*
                          shortcuts)))
          (throw (ex-info (str "Illegal path part " rname)
                          {:data-model dm
                           :rname rname
                           :resolved resolved
                           :path path})))))))
