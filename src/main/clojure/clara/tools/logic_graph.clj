(ns clara.tools.logic-graph
  (:require [schema.core :as s]
            [clojure.string :as string]
            [clara.rules.schema :as cs]
            [clara.rules.compiler :as com]))

(def ^:private operators #{:and :or :not})

(s/defschema node
  {:type (s/enum :fact :fact-condition :and :or :not :rhs)
   :value s/Any} ; TODO: define node value schema...
  )

(s/defschema edge {:type (s/enum :component-of :inserts :then :used-in)
                   (s/optional-key :value) s/Any})

(s/defschema logic-graph-schema {:nodes {s/Str node}
                                 :edges {(s/pair s/Str "from-node"
                                                 s/Str "to-node")
                                         edge}})

(defn get-productions
  "Returns a sequence of productions from the given sources."
  [sources]
  (mapcat
   #(if (satisfies? com/IRuleSource %)
      (com/load-rules %)
      %)
   sources))

(defn- condition-children
  "Returns a sequence of children for the given condition."
  [condition]
  (if #(operators (cs/condition-type condition))
    (rest condition)
    []))

(defn- condition-seq
  "Returns a sequence of conditions used in the given production,
   include expression nodes."
  [production]
  (tree-seq #(operators (cs/condition-type %))
            #(rest %)
            (if (= 1 (count (:lhs production)))
              (first (:lhs production))
              (into [:and] (:lhs production)))))  ; Add implied and for top-level conditions.

(defn- condition-to-id-map
  "Returns a map associating conditions to node ids"
  [production]

  ;; Recursively walk nested condition and return a map associating each with a unique id.
  (let [conditions (condition-seq production)]

    (into
     {}
     (map (fn [condition index]
            [condition (str index "-" (hash production))])
          conditions
          (range)))))

(defn- fact-to-value [fact-type]
  (if (instance? Class fact-type)
    (.getName ^Class fact-type)
    (str fact-type)))

(defn- fact-to-id
  "Returns a node id for a given fact type."
  [fact-type]
  (str "FT-" (fact-to-value fact-type)))


(defmulti condition-graph
  "Creates a sub graph for the given condition."
  (fn [condition condition-to-id] (cs/condition-type condition)))

(defmethod condition-graph :fact
  [condition condition-to-id]
  (let [condition-id (condition-to-id condition)
        fact-id (fact-to-id (:type condition))]

    {:nodes {condition-id
             {:type :fact-condition
              :value condition}

             fact-id
             {:type :fact
              :value (fact-to-value (:type condition))}}


     :edges {[fact-id condition-id]
             {:type :used-in}}}))

(defmethod condition-graph :accumulator
  [condition condition-to-id]

  (let [condition-id (condition-to-id condition)]

    ;; TODO: add child condition used in accumulator.
    {:nodes {condition-id
             {:type :accum-condition
              :value condition}}

     :edges {}}))

(defn- bool-condition-graph
  [[condition-type & children :as condition] condition-to-id]
  (let [condition-id (condition-to-id condition)]

    {:nodes {condition-id
             {:type condition-type
              :value condition}}

     :edges (into {}
                  (for [child children]
                    [[(condition-to-id child) condition-id]

                     {:type :component-of}]))}))

(defmethod condition-graph :and
  [condition condition-to-id]
  (bool-condition-graph condition condition-to-id))

(defmethod condition-graph :or
  [condition condition-to-id]
  (bool-condition-graph condition condition-to-id))

(defmethod condition-graph :not
  [condition condition-to-id]
  (bool-condition-graph condition condition-to-id))


(defn- get-insertions
  "Returns the insertions done by a production."
  [production]
  (if-let [rhs (:rhs production)]

    (into
     #{}
     (for [expression (tree-seq seq? identity rhs)
           :when (and (list? expression)
                      (= 'clara.rules/insert! (first expression))) ; Find insert! calls
           [create-fact-fn create-fact-args] (rest expression)
           :when (re-matches #"->.*" (name create-fact-fn))] ; Find record constructors.

       ;; Get the class qualified class name as a string.
       (str (string/replace (str (namespace create-fact-fn))  #"-" "_")
            "."
            (subs (name create-fact-fn) 2)))) ; Return the record type.
    #{}))

;; Add all fact type nodes to the graph.
(defn- production-graph
  "Creates a logic graph for the given production."
  [production]
  (let [prod-node-id (str "P-" (hash production))
        condition-to-id (condition-to-id-map production)
        conditions (condition-seq production)
        insertions (get-insertions production)]

    (apply merge-with conj

           ;; Add the nodes for the production and edges between
           ;; its conditions and emitted items.
           {:nodes (into {prod-node-id {:type :production
                                        :value (assoc production
                                                 :meta (meta production))}}

                         ;; Add nodes for all inserted facts.
                         (for [insertion insertions]
                           [(fact-to-id insertion)
                            {:type :fact
                             :value (fact-to-value insertion)}]))

            ;; Create an edge to the first condition in the seq, which
            ;; is either a simple condition or the parent expression.
            :edges (into {[(condition-to-id (first conditions)) prod-node-id]
                          {:type :then}}

                         ;; Add edges to inserted facts.
                         (for [insertion insertions]
                           [[prod-node-id (fact-to-id insertion)]
                            {:type :inserts}]))}

           (for [condition conditions]
             (condition-graph condition condition-to-id)))))




(defn- walk-graph
  "Walks the graph starting with the given node key and the given function to return
   the edges to walk from a node.

   Parameters are:

   * the graph to walk
   * the node key where we start our walk
   * an edges-from-node function that returns the set of edges to walk from a node.
   * a node-from-edge  function that returns which side of the edge to visit,
     to support walking the graph in either direction."
  [{:keys [nodes edges] :as graph} node-key edges-from-node node-from-edge]

  (loop [[current-edge-key & more-edge-keys] (edges-from-node node-key)

         visited #{}
         result {:nodes {node-key (get nodes node-key)}
                 :edges {}}]

    (if-not current-edge-key
      result

      (if (visited current-edge-key)
        (recur more-edge-keys visited result)
        (let [next-node-key (node-from-edge current-edge-key)]
          (recur (concat more-edge-keys (edges-from-node next-node-key))
                 (conj visited current-edge-key)
                 (merge-with conj
                             result
                             {:nodes {next-node-key (get nodes next-node-key)}
                              :edges {current-edge-key (get edges current-edge-key)}})))))))

(defn connects-to
  "Returns the subset of the graph that transitively connects to the given node key."
  [graph node-key]
  (println "TO: " node-key)
  (walk-graph graph
              node-key
              (fn [node-key]
                (filter (fn [[from-key to-key]]
                          (= to-key node-key))
                        (keys (:edges graph))))
              ;; Look at the from part of the edge, which is first in the tuple.
              first))

(defn reachable-from
  "Returns the subset of the graph transitively reachable from the node with the given ID"
  [graph node-key]
  (println "FROM: " node-key)
  (walk-graph graph
              node-key
              (fn [node-key]
                (filter (fn [[from-key to-key]]
                          (= from-key node-key))
                        (keys (:edges graph))))

              ;; Look at the to part of the edge, which is second in the tuple.
              second))

(defn logic-graph
  "Returns a graph of the relationships between rules, constraints, facts, and insertions."
  [sources]

  ;; Get a graph for each production, and merge them together.
  (apply merge-with conj
         (map production-graph (get-productions sources))))

(defn filter-facts
  "Returns sub-graph that includes only nodes connected to
   the given facts. The given facts-regex is a regular expression
   used to match facts."
  [graph facts-regex]
  ;; Identify nodes matching the facts then graph the sub-graph that
  ;; connects to them.
  (apply merge-with conj
         {:nodes {}
          :edges {}}
         (for [[node-key {:keys [type value] :as node}] (:nodes graph)
               :when (and (= :fact type)
                          (re-find facts-regex value))
               subgraph [(connects-to graph node-key) (reachable-from graph node-key)]]
           subgraph)))