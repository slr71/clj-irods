(ns clj-irods.validate
  (:require [clojure-commons.error-codes :as error]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [throw+]]
            [otel.otel :as otel]
            [clj-irods.core :as rods]))

(defn validate
  "Validate a set of things in iRODS.

  Each validation is a vector of a keyword (identifying the kind of validation)
  and any relevant arguments. The validations will be run in the provided order.

  Validations that are equal to nil will be ignored. This makes it easier for callers to add
  conditional validations as in:

      (validate irods
                [:user-exists user zone]
                (when validate-path? [:path-exists path user zone]))

  Available validations and their arguments:

  :user-exists (string or vector, users to check), (string zone)
  :path-exists (string or vector, path or paths to check), (string user), (string zone)
  :path-is-file (string or vector, path or paths to check), (string user), (string zone)
  :path-is-dir (string or vector, path or paths to check), (string user), (string zone)
  :path-readable (string or vector, path or paths to check), (string user), (string zone)
  :path-writeable (string or vector, path or paths to check), (string user), (string zone)
  :path-owned (string or vector, path or paths to check), (string user), (string zone)
  "
  [irods & validations]

  (otel/with-span [s ["validate"]]
    (doseq [v (remove nil? validations)]
      (condp = (first v)
        :user-exists (let [[users zone] (rest v)]
                       (doseq [u (if (vector? users) users [users])]
                         (when (or (nil? u) (= @(rods/user-type irods u zone) :none))
                           (throw+ {:error_code error/ERR_NOT_A_USER
                                    :user u}))))
        :path-exists (let [[paths user zone] (rest v)]
                       (doseq [p (if (vector? paths) paths [paths])]
                         (when (or (nil? p) (= @(rods/object-type irods user zone p) :none))
                           (throw+ {:error_code error/ERR_DOES_NOT_EXIST
                                    :path p}))))
        :path-not-exists (let [[paths user zone] (rest v)]
                           (doseq [p (if (vector? paths) paths [paths])]
                             (when-not (= @(rods/object-type irods user zone p) :none)
                               (throw+ {:error_code error/ERR_EXISTS
                                        :path p}))))
        :path-is-file (let [[paths user zone] (rest v)]
                        (doseq [p (if (vector? paths) paths [paths])]
                          (when-not (= @(rods/object-type irods user zone p) :file)
                            (throw+ {:error_code error/ERR_NOT_A_FILE
                                     :path p}))))
        :path-is-dir (let [[paths user zone] (rest v)]
                       (doseq [p (if (vector? paths) paths [paths])]
                         (when-not (= @(rods/object-type irods user zone p) :dir)
                           (throw+ {:error_code error/ERR_NOT_A_FOLDER
                                    :path p}))))
        :path-readable (let [[paths user zone] (rest v)]
                         (doseq [p (if (vector? paths) paths [paths])]
                           (when-not (contains? #{:read :write :own} @(rods/permission irods user zone p))
                             (throw+ {:error_code error/ERR_NOT_READABLE
                                      :path p
                                      :user user}))))
        :path-writeable (let [[paths user zone] (rest v)]
                         (doseq [p (if (vector? paths) paths [paths])]
                           (when-not (contains? #{:write :own} @(rods/permission irods user zone p))
                             (throw+ {:error_code error/ERR_NOT_READABLE
                                      :path p
                                      :user user}))))
        :path-owned (let [[paths user zone] (rest v)]
                         (doseq [p (if (vector? paths) paths [paths])]
                           (when-not (= :own @(rods/permission irods user zone p))
                             (throw+ {:error_code error/ERR_NOT_READABLE
                                      :path p
                                      :user user}))))
        (log/warn "Unrecognized validation type:" (first v))))))
