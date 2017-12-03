; Copyright (c) 2009-2012 Andrew Boekhoff, Sean Corfield

; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:

; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.

; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns
  ^{:author "Andrew Boekhoff, Sean Corfield",
    :doc "Various wrappers and utilities for the mongodb-java-driver"}
  somnium.congomongo
  (:require [clojure.string]
            [clojure.walk :refer (postwalk)]
            [somnium.congomongo.config :refer [*mongo-config*]]
            [somnium.congomongo.coerce :refer [coerce coerce-fields coerce-index-fields]])
  (:import  [com.mongodb MongoClient MongoClientOptions MongoClientOptions$Builder MongoClientURI
             DB DBCollection DBObject DBRef ServerAddress ReadPreference WriteConcern Bytes DBCursor]
            [com.mongodb.gridfs GridFS]
            [com.mongodb.util JSON]
            [org.bson.types ObjectId]))


(defprotocol StringNamed
  (named [s] "convenience for interchangeably handling keywords, symbols, and strings"))
(extend-protocol StringNamed
  clojure.lang.Named
  (named [s] (name s))
  Object
  (named [s] s))

(def ^{:private true
       :doc "To avoid yet another level of indirection via reflection, use
             wrapper functions for field setters for each type. MongoClientOptions
             only has int and boolean fields."}
      type-to-setter
  {:int (fn [^java.lang.reflect.Field field ^MongoClientOptions options value] (.setInt field options value))
   :boolean (fn [^java.lang.reflect.Field field ^MongoClientOptions options value] (.setBoolean field options value))})

(defn- field->kw
  "Convert camelCase identifier string to hyphen-separated keyword."
  [id]
  (keyword (clojure.string/replace id #"[A-Z]" #(str "-" (Character/toLowerCase ^Character (first %))))))

(def ^:private builder-map
  "A map from keywords to builder invocation functions."
  ;; Be aware that using refelction directly here will also issue Clojure reflection warnings.
  (let [is-builder-method? (fn [^java.lang.reflect.Method f]
                             (let [m (.getModifiers f)]
                               (and (java.lang.reflect.Modifier/isPublic m)
                                    (not (java.lang.reflect.Modifier/isStatic m))
                                    (= MongoClientOptions$Builder (.getReturnType f)))))
        method-name (fn [^java.lang.reflect.Method f] (.getName f))
        builder-call (fn [^MongoClientOptions$Builder m]
                       (eval (list 'fn '[o v]
                                   (list (symbol (str "." m)) 'o 'v))))
        kw-fn-pair (fn [m] [(field->kw m) (builder-call m)])
        method-lookups (->> (.getDeclaredMethods MongoClientOptions$Builder)
                            (filter is-builder-method?)
                            (map method-name)
                            (map kw-fn-pair))]
    (into {} method-lookups)))

(defn mongo-options
  "Return MongoClientOptions, populated by any specified options. e.g.,
     (mongo-options :auto-connect-retry true)"
  [& options]
  (let [option-map (apply hash-map options)
        builder-call (fn [b [k v]]
                       (if-let [f (k builder-map)]
                         (f b v)
                         (throw (IllegalArgumentException.
                                 (str k " is not a valid MongoClientOptions$Builder argument")))))]
    (.build ^MongoClientOptions$Builder (reduce builder-call (MongoClientOptions$Builder.) option-map))))

(defn- make-server-address
  "Convenience to make a ServerAddress without reflection warnings."
  [^String host ^Integer port]
  (ServerAddress. host port))

(defn- make-connection-args
  "Makes a connection with passed database name, host, port and MongoClientOptions"
  [db args]
    (let [instances (take-while #(not (instance? MongoClientOptions %)) args)
          addresses (->> (if (keyword? (first instances))
                           (list (apply array-map instances)) ; Handle legacy connect args
                           instances)
                      (map (fn [{:keys [host port] :or {host "127.0.0.1" port 27017}}]
                             (make-server-address host port))))
          ^MongoClientOptions options (or (first (filter #(instance? MongoClientOptions %) args)) (mongo-options))
          mongo (if (> (count addresses) 1)
                  (MongoClient. ^java.util.List addresses options)
                  (MongoClient. ^ServerAddress (first addresses) options))
          n-db (if db (.getDB mongo db) nil)]
      {:mongo mongo :db n-db}))

(defn- make-connection-uri
  "Makes a connection with a Mongo URI, authenticating if username and password are passed"
  [db]
  (let [^MongoClientURI mongouri (MongoClientURI. db)
        ^MongoClient client (MongoClient. mongouri)
        ^String db (.getDatabase mongouri)
        conn {:mongo client :db (.getDB client db)}
        ^DB db (conn :db)
        ^String username (.getUsername mongouri)
        ^chars password (.getPassword mongouri)]
    (when (and username password)
      (.authenticate db username password))
    conn))

(defn make-connection
  "Connects to one or more mongo instances, returning a connection
that can be used with set-connection! and with-mongo. Each instance is
a map containing values for :host and/or :port.
  May be called with database name and optionally:
    host (default: 127.0.0.1)
    port (default: 27017)
    A MongoClientOptions object
  A MongoClientURI string is also supported and must be prefixed with mongodb://
  If username and password are specified, authenticate will be immediately
  called on the connection."
  ([db]
    (make-connection db {}))
  ([db & args]
    (let [^String dbname (named db)]
      (if (.startsWith dbname "mongodb://")
        (make-connection-uri dbname)
        (make-connection-args dbname args)))))

(defn connection?
  "Returns truth if the argument is a map specifying an active connection."
  [x]
  (and (map? x)
       (contains? x :db)
       (:mongo x)))

(defn ^DB get-db
  "Returns the current connection. Throws exception if there isn't one."
  [conn]
  (assert (connection? conn))
  (:db conn))

(defn close-connection
  "Closes the connection, and unsets it as the active connection if necessary"
  [conn]
  (assert (connection? conn))
  (if (= conn *mongo-config*)
    (if (thread-bound? #'*mongo-config*)
      (set! *mongo-config* nil)
      (alter-var-root #'*mongo-config* (constantly nil))))
  (.close ^MongoClient (:mongo conn)))

(defmacro with-mongo
  "Makes conn the active connection in the enclosing scope.

  When with-mongo and set-connection! interact, last one wins"
  [conn & body]
  `(do
     (let [c# ~conn]
       (assert (connection? c#))
       (binding [*mongo-config* c#]
         ~@body))))

(defmacro with-db
  "Make dbname the active database in the enclosing scope.

  When with-db and set-database! interact, last one wins."
  [dbname & body]
  `(let [^DB db# (.getDB ^MongoClient (:mongo *mongo-config*) (name ~dbname))]
     (binding [*mongo-config* (assoc *mongo-config* :db db#)]
       ~@body)))

(defn set-connection!
  "Makes the connection active. Takes a connection created by make-connection.

When with-mongo and set-connection! interact, last one wins"
  [connection]
  (alter-var-root #'*mongo-config*
                  (constantly connection)
                  (when (thread-bound? #'*mongo-config*)
                    (set! *mongo-config* connection))))

(defn mongo!
  "Creates a Mongo object and sets the default database.

Does not support replica sets, and will be deprecated in future
releases.  Please use 'make-connection' in combination with
'with-mongo' or 'set-connection!' instead.

   Keyword arguments include:
   :host -> defaults to localhost
   :port -> defaults to 27017
   :db   -> defaults to nil (you'll have to set it anyway, might as well do it now.)"
  {:arglists '([:db ? :host "localhost" :port 27017])}
  [& {:keys [db host port]
      :or {db nil host "localhost" port 27017}}]
  (set-connection! (make-connection db :host host :port port))
  true)

(defn authenticate
  "Authenticate against either the current or a specified database connection."
  ([conn username password]
     (let [db (get-db conn)]
       (when-not (.isAuthenticated db)
         (.authenticate db
                        ^String username
                        (.toCharArray ^String password)))))
  ([username password]
     (authenticate *mongo-config* username password)))

(definline ^DBCollection get-coll
  "Returns a DBCollection object"
  [collection]
  `(.getCollection (get-db *mongo-config*)
     ^String (named ~collection)))

(def write-concern-map
  {:acknowledged         WriteConcern/ACKNOWLEDGED
   :errors-ignored       WriteConcern/ERRORS_IGNORED
   :fsynced              WriteConcern/FSYNCED
   :journaled            WriteConcern/JOURNALED
   :majority             WriteConcern/MAJORITY
   :replica-acknowledged WriteConcern/REPLICA_ACKNOWLEDGED
   :unacknowledged       WriteConcern/UNACKNOWLEDGED
   ;; these are pre-2.10.x names for write concern:
   :fsync-safe    WriteConcern/FSYNC_SAFE  ;; deprecated - use :fsynced
   :journal-safe  WriteConcern/JOURNAL_SAFE ;; deprecated - use :journaled
   :none          WriteConcern/NONE ;; deprecated - use :errors-ignored
   :normal        WriteConcern/NORMAL ;; deprecated - use :unacknowledged
   :replicas-safe WriteConcern/REPLICAS_SAFE ;; deprecated - use :replica-acknowledged
   :safe          WriteConcern/SAFE ;; deprecated - use :acknowledged
   ;; these are left for backward compatibility but are deprecated:
   :replica-safe WriteConcern/REPLICAS_SAFE
   :strict       WriteConcern/SAFE
   })

(defn set-write-concern
  "Sets the write concern on the connection. Setting is a key in the
  write-concern-map above."
  [connection setting]
  (assert (contains? (set (keys write-concern-map)) setting))
  (.setWriteConcern (get-db connection)
                    ^WriteConcern (get write-concern-map setting)))

(defn set-collection-write-concern!
  "Sets this write concern as default for a collection."
  [collection write-concern]
  (if-let [concern (get write-concern-map write-concern)]
    (.setWriteConcern (get-coll collection) concern)
    (throw (IllegalArgumentException. (str "Unknown write concern " write-concern ".")))))

(defn get-collection-write-concern
  "Gets the currently set write concern for a collection."
  [collection]
    (.getWriteConcern (get-coll collection)))

(defn- illegal-write-concern
  [write-concern]
  (throw (IllegalArgumentException. (str write-concern " is not a valid WriteConcern alias"))))

;; add some convenience fns for manipulating object-ids
(definline object-id ^ObjectId [^String s]
  `(ObjectId. ~s))

;; Make ObjectIds printable under *print-dup*, hiding the
;; implementation-dependent ObjectId class
(defmethod print-dup ObjectId [^ObjectId x ^java.io.Writer w]
  (.write w (str "#=" `(object-id ~(.toString x)))))

(defn get-timestamp
  "Pulls the timestamp from an ObjectId or a map with a valid ObjectId in :_id."
  [obj]
  (when-let [^ObjectId id (if (instance? ObjectId obj) obj (:_id obj))]
    (.getTime id)))

(defn db-ref
  "Convenience DBRef constructor."
  [ns id]
  (DBRef. (get-db *mongo-config*)
          ^String (named ns)
          ^Object id))

(defn db-ref? [x]
  (instance? DBRef x))

(defn collection-exists?
  "Query whether the named collection has been created within the DB."
  [collection]
  (.collectionExists (get-db *mongo-config*)
                     ^String (named collection)))

(defn create-collection!
  "Explicitly create a collection with the given name, which must not already exist.

   Most users will not need this function, and will instead allow
   MongoDB to implicitly create collections when they are written
   to. This function exists primarily to allow the creation of capped
   collections, and so supports the following keyword arguments:

   :capped -> boolean: if the collection is capped
   :size   -> int: collection size (in bytes)
   :max    -> int: max number of documents."
  {:arglists
   '([collection :capped :size :max])}
  ([collection & {:keys [capped size max] :as options}]
     (.createCollection (get-db *mongo-config*)
                        ^String (named collection)
                        (coerce options [:clojure :mongo]))))

(def query-option-map
  {:tailable    Bytes/QUERYOPTION_TAILABLE
   :slaveok     Bytes/QUERYOPTION_SLAVEOK
   :oplogreplay Bytes/QUERYOPTION_OPLOGREPLAY
   :notimeout   Bytes/QUERYOPTION_NOTIMEOUT
   :awaitdata   Bytes/QUERYOPTION_AWAITDATA})

(defn calculate-query-options
  "Calculates the cursor's query option from a list of options"
   [options]
   (reduce bit-or 0 (map query-option-map (if (keyword? options)
                                            (list options)
                                            options))))

(def ^:private read-preference-map
  "Private map of facory functions of ReadPreferences to aliases."
  {:nearest (fn nearest ([] (ReadPreference/nearest)) ([first-tag remaining-tags] (ReadPreference/nearest first-tag remaining-tags)))
   :primary (fn primary ([] (ReadPreference/primary)) ([_ _] (throw (IllegalArgumentException. "Read preference :primary does not accept tag sets."))))
   :primary-preferred (fn primary-preferred ([] (ReadPreference/primaryPreferred)) ([first-tag remaining-tags] (ReadPreference/primaryPreferred first-tag remaining-tags)))
   :secondary (fn secondary ([] (ReadPreference/secondary)) ([first-tag remaining-tags] (ReadPreference/secondary first-tag remaining-tags)))
   :secondary-preferred (fn secondary-preferred ([] (ReadPreference/secondaryPreferred)) ([first-tag remaining-tags] (ReadPreference/secondaryPreferred first-tag remaining-tags)))})

(defn read-preference
  "Creates a ReadPreference from an alias and optional tag sets. Valid aliases are :nearest,
   :primary, :primary-preferred, :secondary and :secondary-preferred."
  {:arglists '([preference {:first-tag "value"} {:other-tag-set "other-value"}])}
  [preference & tags]
  (if-let [pref-factory (get read-preference-map preference)]
    (if (empty? tags)
      (pref-factory)
      (pref-factory
        (coerce (first tags) [:clojure :mongo ])
        (into-array com.mongodb.DBObject (coerce (rest tags) [:clojure :mongo ] :many true)))
      )
    (throw (IllegalArgumentException. (str preference " is not a valid ReadPreference alias.")))))

(defn set-read-preference
  "Sets the read preference on the connection (you may supply a ReadPreference or a valid alias)."
  [connection preference]
  (let [p (if (instance? ReadPreference preference)
            preference
            (read-preference preference))]
    (.setReadPreference (get-db connection) ^ReadPreference p)))

(defn set-collection-read-preference!
  "Sets the read preference as default for a collection."
  [collection preference & opts]
  (let [pref (apply read-preference preference opts)]
    (.setReadPreference (get-coll collection) pref)
    pref))

(defn get-collection-read-preference
  "Returns the currently set read preference for a collection"
  [collection]
  (.getReadPreference (get-coll collection)))

(defn fetch
  "Fetches objects from a collection.
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where    -> takes a query map
   :only     -> takes an array of keys to retrieve
   :as       -> what to return, defaults to :clojure, can also be :json or :mongo
   :from     -> argument type, same options as above
   :skip     -> number of records to skip
   :limit    -> number of records to return
   :one?     -> defaults to false, use fetch-one as a shortcut
   :count?   -> defaults to false, use fetch-count as a shortcut
   :explain? -> returns performance information on the query, instead of rows
   :sort     -> sort the results by a specific key
   :options  -> query options [:tailable :slaveok :oplogreplay :notimeout :awaitdata]
   :hint     -> tell the query which index to use (name (string) or [:compound :index] (seq of keys))
   :read-preferences -> read preferences (e.g. :primary or ReadPreference instance)"
  {:arglists
   '([collection :where :only :limit :skip :as :from :one? :count? :sort :hint :explain? :options :read-preferences])}
  [coll & {:keys [where only as from one? count? limit skip sort hint options explain? read-preferences]
           :or {where {} only [] as :clojure from :clojure
                one? false count? false limit 0 skip 0 sort nil hint nil options [] explain? false}}]
  (when (and one? sort)
    (throw (IllegalArgumentException. "Fetch :one? (or fetch-one) can't be used with :sort.
You should use fetch with :limit 1 instead."))); one? and sort should NEVER be called together
  (when (and one? (or read-preferences (not= [] options) (not= 0 limit) hint explain?))
    (throw (IllegalArgumentException. "At the moment, fetch-one doesn't support read-preferences, options, limit or hint"))) ;; these are allowed but not implemented here
  (when-not (or (nil? hint)
                (string? hint)
                (and (instance? clojure.lang.Sequential hint)
                     (every? #(or (keyword? %)
                                  (and (instance? clojure.lang.Sequential %)
                                       (= 2 (count %))
                                       (-> % first keyword?)
                                       (-> % second #{1 -1})))
                             hint)))
    (throw (IllegalArgumentException. ":hint requires a string name of the index, or a seq of keywords that is the index definition")))
  (let [n-where (coerce where [from :mongo])
        n-only  (coerce-fields only)
        n-col   (get-coll coll)
        n-limit (if limit (- 0 (Math/abs (long limit))) 0)
        n-sort (when sort (coerce sort [from :mongo]))
        n-options (calculate-query-options options)
        n-hint (cond (string? hint) hint
                     (nil? hint) nil
                     :else (coerce-index-fields hint))
        n-preferences (cond
                        (nil? read-preferences) nil
                        (instance? ReadPreference read-preferences) read-preferences
                        :else (somnium.congomongo/read-preference read-preferences))]
    (cond
      count? (.getCount n-col n-where n-only)
      one?   (if-let [m (.findOne
                         ^DBCollection n-col
                         ^DBObject n-where
                         ^DBObject n-only)]
               (coerce m [:mongo as]) nil)
      :else  (when-let [cursor (DBCursor. ^DBCollection n-col
                                      ^DBObject n-where
                                      ^DBObject n-only
                                      ^ReadPreference n-preferences)]
               (when n-hint
                 (.hint cursor n-hint))
               (when n-options
                 (.setOptions cursor n-options))
               (when n-sort
                 (.sort cursor n-sort))
               (when skip
                 (.skip cursor skip))
               (when n-limit
                 (.limit cursor n-limit))
               (if explain?
                 (coerce (.explain cursor) [:mongo as] :many false)
                 (coerce cursor [:mongo as] :many true))))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

;; add fetch-by-id fn
(defn fetch-by-id [col id & options]
  (apply fetch col (concat options [:one? true :where {:_id id}])))

(defn fetch-by-ids [col ids & options]
  (apply fetch col (concat options [:where {:_id {:$in ids}}])))

(defn with-ref-fetching
  "Returns a decorated fetcher fn which eagerly loads db-refs."
  [fetcher]
  (fn [& args]
    (let [as (or (second (drop-while (partial not= :as) args))
                 :clojure)
          f  (fn [[k v]]
               [k (if (db-ref? v)
                    (coerce (.fetch ^DBRef v) [:mongo as])
                    v)])]
      (postwalk (fn [x]
                  (if (map? x)
                    (into {} (map f x))
                    x))
                (apply fetcher args)))))

(defn distinct-values
  "Queries a collection for the distinct values of a given key.
   Returns a vector of the values by default (but see the :as keyword argument).
   The key (a String) can refer to a nested object, using dot notation, e.g., \"foo.bar.baz\".

   Optional arguments include
   :where  -> a query object.  If supplied, distinct values from the result of the query on the collection (rather than from the entire collection) are returned.
   :from   -> specifies what form a supplied :where query is in (:clojure, :json, or :mongo).  Defaults to :clojure.  Has no effect if there is no :where query.
   :as     -> results format (:clojure, :json, or :mongo).  Defaults to :clojure."
  {:arglists
   '([collection key :where :from :as])}
  [coll ^String k & {:keys [where from as]
             :or {where {} from :clojure as :clojure}}]
  (let [^DBObject query (coerce where [from :mongo])]
    (coerce (.distinct ^DBCollection (get-coll coll) k query)
            [:mongo as])))

(defn insert!
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes optional from and to keyword arguments. To insert
   as a side-effect only specify :to as nil."
  {:arglists '([coll obj {:many false :from :clojure :to :clojure :write-concern nil}])}
  [coll obj & {:keys [from to many write-concern]
               :or {from :clojure to :clojure many false}}]
  (let [coerced-obj (coerce obj [from :mongo] :many many)
        list-obj (if many coerced-obj (list coerced-obj))
        res (if write-concern
              (if-let [wc (write-concern write-concern-map)]
                (.insert ^DBCollection (get-coll coll) ^java.util.List list-obj ^WriteConcern wc)
                (illegal-write-concern write-concern))
              (.insert ^DBCollection (get-coll coll) ^java.util.List list-obj))]
    (coerce coerced-obj [:mongo to] :many many)))

(defn mass-insert!
  {:arglists '([coll objs {:from :clojure :to :clojure}])}
  [coll objs & {:keys [from to write-concern]
                :or {from :clojure to :clojure}}]
  (insert! coll objs :from from :to to :many true :write-concern write-concern))

;; should this raise an exception if _ns and _id aren't present?
(defn update!
   "Alters/inserts a map in a collection. Overwrites existing objects.
   The shortcut forms need a map with valid :_id and :_ns fields or
   a collection and a map with a valid :_id field."
   {:arglists '([collection old new {:upsert true :multiple false :as :clojure :from :clojure :write-concern nil}])}
   [coll old new & {:keys [upsert multiple as from write-concern]
                    :or {upsert true multiple false as :clojure from :clojure}}]
   (coerce (if write-concern
             (if-let [wc (write-concern write-concern-map)]
               (.update ^DBCollection  (get-coll coll)
                        ^DBObject (coerce old [from :mongo])
                        ^DBObject (coerce new [from :mongo])
                        upsert multiple ^WriteConcern wc))
             (.update ^DBCollection  (get-coll coll)
                      ^DBObject (coerce old [from :mongo])
                      ^DBObject (coerce new [from :mongo])
                      upsert multiple)) [:mongo as]))

(defn fetch-and-modify
  "Finds the first document in the query and updates it.
   Parameters:
       coll         -> the collection
       where        -> query to match
       update       -> update to apply
       :only        -> fields to be returned
       :sort        -> sort to apply before picking first document
       :remove?     -> if true, document found will be removed
       :return-new? -> if true, the updated document is returned,
                       otherwise the old document is returned
                       (or it would be lost forever)
       :upsert?     -> do upsert (insert if document not present)"
  {:arglists '([collection where update {:only nil :sort nil :remove? false
                                         :return-new? false :upsert? false :from :clojure :as :clojure}])}
  [coll where update & {:keys [only sort remove? return-new? upsert? from as]
                        :or {only nil sort nil remove? false
                             return-new? false upsert? false from :clojure as :clojure}}]
  (coerce (.findAndModify ^DBCollection (get-coll coll)
                          ^DBObject (coerce where [from :mongo])
                          ^DBObject (coerce-fields only)
                          ^DBObject (coerce sort [from :mongo])
                          remove?
                          ^DBObject (coerce update [from :mongo])
                          return-new? upsert?) [:mongo as]))


(defn destroy!
   "Removes map from collection. Takes a collection name and
    a query map"
   {:arglists '([collection where {:from :clojure :write-concern nil}])}
   [c q & {:keys [from write-concern]
           :or {from :clojure}}]
   (if write-concern
     (if-let [wc (write-concern write-concern-map)]
       (.remove (get-coll c) ^DBObject (coerce q [from :mongo]) ^WriteConcern wc)
       (illegal-write-concern write-concern))
     (.remove (get-coll c) ^DBObject (coerce q [from :mongo]))))

(defn add-index!
  "Adds an index on the collection for the specified fields if it does not exist.  Ordering of fields is
   significant; an index on [:a :b] is not the same as an index on [:b :a].

   By default, all fields are indexed in ascending order.  To index a field in descending order, specify it as
   a vector with a direction signifier (i.e., -1), like so:

   [:a [:b -1] :c]

   This will generate an index on:

      :a ascending, :b descending, :c ascending

   Similarly, [[:a 1] [:b -1] :c] will generate the same index (\"1\" indicates ascending order, the default).

    Options include:
    :name   -> defaults to the system-generated default
    :unique -> defaults to false
    :sparse -> defaults to false
    :background -> defaults to false"
   {:arglists '([collection fields {:name nil :unique false :sparse false}])}
   [c f & {:keys [name unique sparse background]
           :or {name nil unique false sparse false background false}}]
   (-> (get-coll c)
       (.ensureIndex (coerce-index-fields f) ^DBObject (coerce (merge {:unique unique :sparse sparse :background background}
                                                                       (if name {:name name}))
                                                                [:clojure :mongo]))))
(defn drop-index!
  "Drops an index on the collection for the specified fields.

  `index` may be a vector representing the key(s) of the index (see somnium.congomongo/add-index! for the
  expected format).  It may also be a String or Keyword, in which case it is taken to be the name of the
  index to be deleted.

  Due to how the underlying MongoDB driver works, if you defined an index with a custom name, you *must*
  delete the index using that name, and not the keys."
  [coll index]
  (if (vector? index)
    (.dropIndex (get-coll coll) (coerce-index-fields index))
    (.dropIndex (get-coll coll) ^String (coerce index [:clojure :mongo]))))

(defn drop-all-indexes!
  "Drops all indexes from a collection"
  [coll]
  (.dropIndexes (get-coll coll)))

(defn get-indexes
  "Get index information on collection"
  {:arglists '([collection :as (:clojure)])}
   [coll & {:keys [as]
            :or {as :clojure}}]
   (map #(into {} %) (.getIndexInfo (get-coll coll))))

(defn aggregate
  "Executes a pipeline of operations using the Aggregation Framework.
   Returns map {:serverUsed ... :result ... :ok ...}
   :serverUsed - string representing server address
   :result     - the result of the aggregation (if successful)
   :ok         - 1.0 for success
   Requires MongoDB 2.2!"
  {:arglists '([coll op & ops {:from :clojure :to :clojure}])}
  [coll op & ops-and-from-to]
  (let [ops (take-while (complement keyword?) ops-and-from-to)
        from-and-to (drop-while (complement keyword?) ops-and-from-to)
        {:keys [from to] :or {from :clojure to :clojure}} from-and-to]
    (coerce (.getCommandResult (.aggregate (get-coll coll)
                                           (coerce op [from :mongo])
                                           (into-array DBObject (map #(coerce % [from :mongo]) ops))))
            [:mongo to])))

(defn command
  "Executes a database command."
  {:arglists '([cmd {:options nil :from :clojure :to :clojure}])}
  [cmd & {:keys [options from to]
          :or {options nil from :clojure to :clojure}}]
  (let [db (get-db *mongo-config*)
        coerced (coerce cmd [from :mongo])]
    (coerce (if options
              (.command db ^DBObject coerced (int options))
              (.command db ^DBObject coerced))
            [:mongo to])))

(defn drop-database!
 "drops a database from the mongo server"
 [title]
 (.dropDatabase ^MongoClient (:mongo *mongo-config*) ^String (named title)))

(defn set-database!
  "atomically alters the current database"
  [title]
  (if-let [db (.getDB ^MongoClient (:mongo *mongo-config*) ^String (named title))]
    (alter-var-root #'*mongo-config* merge {:db db})
    (throw (RuntimeException. (str "database with title " title " does not exist.")))))

;;;; go ahead and have these return seqs

(defn databases
  "List databases on the mongo server" []
  (seq (.getDatabaseNames ^MongoClient (:mongo *mongo-config*))))

(defn collections
  "Returns the set of collections stored in the current database" []
  (seq (.getCollectionNames (get-db *mongo-config*))))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop ^DBCollection (.getCollection (get-db *mongo-config*)
                                       ^String (named collection))))

;;;; GridFS, contributed by Steve Purcell
;;;; question: keep the camelCase keyword for :contentType ?

(definline ^GridFS get-gridfs
  "Returns a GridFS object for the named bucket"
  [bucket]
  `(GridFS. (get-db *mongo-config*) ^String (named ~bucket)))

;; The naming of :contentType is ugly, but consistent with that
;; returned by GridFSFile
(defn insert-file!
  "Insert file data into a GridFS. Data should be either a File,
   InputStream or byte array.
   Options include:
   :filename    -> defaults to nil
   :contentType -> defaults to nil
   :metadata    -> defaults to nil"
  {:arglists '([fs data {:filename nil :contentType nil :metadata nil}])}
  [fs data & {:keys [^String filename ^String contentType ^DBObject metadata]
              :or {filename nil contentType nil metadata nil}}]
  (let [^com.mongodb.gridfs.GridFSInputFile f (.createFile ^GridFS (get-gridfs fs) data)]
    (if filename (.setFilename f ^String filename))
    (if contentType (.setContentType f contentType))
    (if metadata (.setMetaData f (coerce metadata [:clojure :mongo])))
    (.save f)
    (coerce f [:mongo :clojure])))

(defn destroy-file!
   "Removes file from gridfs. Takes a GridFS name and
    a query map"
   {:arglists '([fs where {:from :clojure}])}
   [fs q & {:keys [from]
            :or {from :clojure}}]
   (.remove (get-gridfs fs)
            ^DBObject (coerce q [from :mongo])))

(defn fetch-files
  "Fetches objects from a GridFS
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where  -> takes a query map
   :from   -> argument type, same options as above
   :one?   -> defaults to false, use fetch-one-file as a shortcut"
  {:arglists
   '([fs :where :from :one?])}
  [fs & {:keys [where from one?]
         :or {where {} from :clojure one? false}}]
  (let [n-where (coerce where [from :mongo])
        n-fs   (get-gridfs fs)]
    (if one?
      (if-let [m (.findOne ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure]) nil)
      (if-let [m (.find ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure] :many true) nil))))

(defn fetch-one-file [fs & options]
  (apply fetch-files fs (concat options '[:one? true])))

(defn write-file-to
  "Writes the data stored for a file to the supplied output, which
   should be either an OutputStream, File, or the String path for a file."
  [fs file out]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) (coerce file [:clojure :mongo]))]
    ;; since .writeTo is overloaded and we can pass different types, we cannot remove the reflection warning:
    (.writeTo f out)))

(defn stream-from
  "Returns an InputStream from the GridFS file specified"
  [fs file]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) (coerce file [:clojure :mongo]))]
    (.getInputStream f)))

(defn server-eval
  "Sends javascript to the server to be evaluated. js should define a function that takes no arguments. The server will call the function."
  [js & args]
  (let [db (get-db *mongo-config*)
        m (.doEval db js (into-array Object args))]
    (let [result (coerce m [:mongo :clojure])]
      (if (= 1.0 (:ok result)) ;; In Clojure 1.3.0, 1 != 1.0 so we must compare against 1.0 instead (the JS stuff always returns floats)
        (:retval result)
        (throw (Exception. ^String (format "failure executing javascript: %s" (str result))))))))

(defn map-reduce
  "Performs a map-reduce job on the server.

  Mandatory arguments
  collection -> the collection to run the job on
  mapfn -> a JavaScript map function, as a String.  Should take no arguments.
  reducefn -> a JavaScript reduce function, as a String.  Should take two arguments: a key, and a corresponding array of values
  out -> output descriptor
      With MongoDB 1.8, there are many options:
          a collection name (String or Keyword): output is saved in the named collection, removing any data that previously existed there.
      Or, a configuration map:
          {:replace collection-name}: same as above
          {:merge collection-name}: incorporates results of the MapReduce with any data already in the collection
          {:reduce collection-name}: further reduces with any data already in the collection
          {:inline 1}: creates no collection, and returns the results directly

  See http://www.mongodb.org/display/DOCS/MapReduce for more information, as well as the test code in congomongo_test.clj.

  Optional Arguments
  :out-from    -> indicates what form the out parameter is specified in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :query       -> a query map against collection; if this is specified, the map-reduce job is run on the result of this query instead of on the collection as a whole.
  :query-from  -> if query is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :sort        -> if you want query sorted (for optimization), specify a map of sort clauses here.
  :sort-from   -> if sort is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :limit       -> the number of objects to return from a query collection (defaults to 0; that is, everything).  This pertains to query, NOT the result of the overall map-reduce job!
  :finalize    -> a finalizaton function (JavaScript, as a String).  Should take two arguments: a key and a single value (not an array of values).
  :scope       -> a scope object; variables in the object will be available in the global scope of map, reduce, and finalize functions.
  :scope-from  -> if scope is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :output      -> if you want the resulting documents from the map-reduce job, specify :documents; otherwise, if you want the name of the result collection as a keyword, specify :collection.
                  Defaults to :documents.  If the value of 'out' is {:inline 1}, you will get documents, regardless of what you actually put here.
  :as          -> if :output is set to :documents, determines the form the results take (:clojure, :json, or :mongo) (has no effect if :output is set to :collection; that is always returned as a Clojure keyword).
"
  {:arglists
   '([collection mapfn reducefn out :out-from :query :query-from :sort :sort-from :limit :finalize :scope :scope-from :output :as])}
  [collection mapfn reducefn out & {:keys [out-from query query-from sort sort-from limit finalize scope scope-from output as]
                                    :or {out-from :clojure query nil query-from :clojure sort nil sort-from :clojure
                                         limit nil finalize nil scope nil scope-from :clojure output :documents as :clojure}}]
  (let [;; BasicDBObject requires key-value pairs in the correct order... apparently the first one
        ;; must be :mapreduce
        mr-query (->> [[:mapreduce collection]
                       [:map mapfn]
                       [:reduce reducefn]
                       [:out (coerce out [out-from :mongo])]
                       [:verbose true]
                       (when query [:query (coerce query [query-from :mongo])])
                       (when sort [:sort (coerce sort [sort-from :mongo])])
                       (when limit [:limit limit])
                       (when finalize [:finalize finalize])
                       (when scope [:scope (coerce scope [scope-from :mongo])])]
                      (remove nil?)
                      flatten
                      (apply array-map))
        mr-query (coerce mr-query [:clojure :mongo])
        ^com.mongodb.MapReduceOutput result (.mapReduce (get-coll collection) ^DBObject mr-query)]
    (if (or (= output :documents)
            (= (coerce out [out-from :clojure])
               {:inline 1}))
      (coerce (.results result) [:mongo as] :many true)
      (-> (.getOutputCollection result)
            .getName
            keyword))))

(defn group
  "Performs group operation on given collection

  Parameters:
  coll         -> the collection
  :reducefn    -> a javascript reduce function as a string
  :where       -> query to match
  :key         -> fields to group-by
  :keyfn       -> string with javascript function returning a key object
  :initial     -> initial value for reduce function
  :finalizefn  -> string containing javascript function to be run on each item in result set just before return

  See http://www.mongodb.org/display/DOCS/Aggregation for more information"
  {:arglists '([coll {:key nil :keyfn nil :reducefn nil :where nil :finalizefn nil
                      :initial nil :as :clojure}])}
  [coll & {:keys [key keyfn reducefn where finalizefn initial as]
           :or {key nil keyfn nil reducefn nil where nil finalizefn nil
                initial nil as :clojure}}]
  (coerce (.group ^DBCollection
            (get-coll coll)
            ^DBObject
            (coerce (into {} (filter second {:key (when key (coerce-fields key))
                     :$keyf keyfn
                     :$reduce reducefn
                     :finalize finalizefn
                     :initial initial
                     :cond where})) [:clojure :mongo ])) [:mongo as]))