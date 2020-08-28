(ns clj-irods.cache-tools
  (:require [slingshot.slingshot :refer [try+ throw+]]
            [clojure.tools.logging :as log] 
            ))

(defn rethrow-if-error
  "Takes a return value. If it is an error as created by `do-or-error`,
  re-throws that error."
  [ret]
  (if (::error ret)
    (throw+ (::error ret))
    ret))

(defn- do-or-error
  "Takes an action and tries to execute it. If it throws an error, returns an
  object with the error at a known key."
  [action]
  (log/info "resolving cached delay")
  (try+
    (action)
    (catch Object o
      {::error o})))

(defn- assoc-in-empty
  [m ks v]
  (when-not (get-in m ks)
    (log/info "updating cache:" ks)
    (assoc-in m ks v)))

(defn- do-and-store
  "Takes a cache, action, and location in the cache. Puts a `delay` into the
  cache at that location which will execute `do-or-error` with the provided
  action."
  [cache action ks]
  (log/info "ensuring cache:" ks)
  (let [store (delay (do-or-error action))]
    (-> cache
        (swap! assoc-in-empty ks store)
        (get-in ks))))

(defn cached-or-do
  "Takes a cache, action, and location in the cache. If the location in the
  cache has something, returns it, otherwise calls `do-or-store` to populate it
  (with a `delay`). Returns the resulting delay, either way."
  [cache action ks]
  (or (get-in @cache ks)
      (do-and-store cache action ks)))

(defn cached-or-agent
  "Takes a cache, action, thread pool, and location in the cache. If the
  location in the cache has something, return it, deref'd, wrapped in
  `rethrow-if-error` and a delay, otherwise initiate an agent and run `action`
  in the specified `pool`. `action` should return something that can be
  deref'd, and will be."
  [cache action pool ks]
  (log/info "cache or agent:" ks)
  (let [cached (get-in @cache ks)]
    (if (and (delay? cached) (realized? cached))
      (delay (rethrow-if-error @cached))
      (let [ag (agent nil)]
        (send-via pool ag (fn [n] @(action)))
        (delay (await ag) (rethrow-if-error @ag))))))

(defn cached-or-nil
  "Takes a cache and location in the cache. If the location in the cache has something and it's a realized delay, return it wrapped in rethrow-if-error and a new delay. Otherwise, return nil (*not* in a delay, for clarity)."
  [cache ks]
  (log/info "try from cache:" ks)
  (let [cached (get-in @cache ks)]
    (when (and (delay? cached) (realized? cached))
      (delay (rethrow-if-error @cached)))))
