(ns cantata.core
  (:require [cantata.util :as cu]
            [cantata.data-model :as cdm]
            [cantata.sql :as sql]
            [clojure.java.jdbc :as jd]))

(defmacro verbose
  "Causes any wrapped queries or statements to print lots of logging
  information during execution. See also 'debug'"
  [& body]
  `(binding [cu/*verbose* true]
     ~@body))

(defn data-model [& entity-specs]
  (apply cdm/data-model entity-specs))

(defn reflect-data-model [ds & entity-specs]
  (apply cdm/reflect-data-model ds entity-specs))

(defn- normalize-db-spec [db-spec]
  (cond
    (map? db-spec) db-spec
    (string? db-spec) {:connection-uri db-spec}
    (instance? java.net.URI db-spec) (normalize-db-spec (str db-spec))
    :else (throw (ex-info "Unrecognized db-spec format" {:db-spec db-spec}))))

(defn data-source [db-spec & model+opts]
  (let [arg1 (first model+opts)
        [dm opts] (if (keyword? arg1)
                    [nil model+opts]
                    [arg1 (rest model+opts)])
        {:keys [reflect quoting]} (apply hash-map opts)
        dm (if reflect
             (apply reflect-data-model db-spec (if (map? dm) (vals dm) dm))
             dm)]
    (cond-> (normalize-db-spec db-spec)
            dm (assoc ::data-model dm)
            quoting (assoc ::quoting quoting))))

(defn pooled-data-source [db-spec & model+opts]
  (apply data-source
         (sql/create-pool (normalize-db-spec db-spec))
         model+opts))

(defn with-query-rows*
  ([ds qargs body-fn]
    (with-query-rows* ds (::data-model ds) qargs body-fn))
  ([ds dm qargs body-fn]
    (sql/query ds dm qargs body-fn)))

;; supporting optional dm arg makes this really awkward
#_(defmacro with-query-rows
   {:arglists '([ds q cols rows & body] [ds dm q cols rows & body])}
   [ds & args]
   (let [[dm q cols rows & body] (if (symbol? (nth args 3))
                                   (cons `(::data-model ~ds) args)
                                   args)]
     `(with-query-rows* ~ds ~dm ~q
       (fn [~cols ~rows]
         ~@body))))

(defn ^:private get-dm-qargs [ds qargs]
  (if (cdm/data-model? (first qargs))
    [(first qargs) (rest qargs)]
    [(::data-model ds) qargs]))

(defn query*
  [ds & qargs]
  (let [[dm qargs] (get-dm-qargs ds qargs)]
    (with-query-rows* ds dm qargs
      (fn [cols rows]
        [cols rows]))))

(defn query
  [ds & qargs]
  (let [[dm qargs] (get-dm-qargs ds qargs)]
    (with-query-rows* ds dm qargs
      (fn [cols rows]
        (mapv #(cu/zip-ordered-map cols %) rows)))))

(defn- add-limit-1 [qargs]
  (let [qargs1 (first qargs)]
    (if (string? qargs1) ;support plain SQL
      (if (re-find #"(?i)\blimit\s+\d+" qargs1)
        qargs
        (cons (str qargs1 " LIMIT 1") (rest qargs)))
      (concat qargs [:limit 1]))))

(defn query1 [ds & qargs]
  (first
    (apply query ds (add-limit-1 qargs))))

(defn by-id [ds & qargs])

(defn query-count [ds & qargs]
  (let [[dm qargs] (get-dm-qargs ds qargs)]
    (sql/query-count ds dm qargs)))

(defn query-count* [ds & qargs]
  (let [[dm qargs] (get-dm-qargs ds qargs)]
    (sql/query-count ds dm qargs :flat true)))

(defn save! [ds dm ename changes opts])

(defn delete! [ds dm ename pred opts])

;; TODO: helper fns - entities, entity, rels, etc - resolve-path - to-sql

(defmacro transaction [binding & body]
  `(jd/db-transaction ~binding ~@body))

(defmacro rollback! [ds]
  `(jd/db-set-rollback-only! ~ds))

(defmacro unset-rollback! [ds]
  `(jd/db-unset-rollback-only! ~ds))

(defmacro rollback? [ds]
  `(jd/db-is-rollback-only ~ds))
