(ns elephantdb.service
  (:import [java.util.concurrent.locks ReentrantReadWriteLock])
  (:import [elephantdb.generated ElephantDB ElephantDB$Iface])
  (:import [elephantdb Shutdownable client])
  (:require [elephantdb [domain :as domain] [thrift :as thrift] [shard :as shard]])
  (:use [elephantdb config log util hadoop loader]))



  ; Value get(1: string domain, 2: binary key)
  ;   throws (1: DomainNotFoundException dnfe, 2: HostsDownException hde, 3: DomainNotLoadedException dnle);
  ; Value getString(1: string domain, 2: string key)
  ;   throws (1: DomainNotFoundException dnfe, 2: HostsDownException hde, 3: DomainNotLoadedException dnle);
  ; Value getInt(1: string domain, 2: i32 key)
  ;   throws (1: DomainNotFoundException dnfe, 2: HostsDownException hde, 3: DomainNotLoadedException dnle);
  ; Value getLong(1: string domain, 2: i64 key)
  ;   throws (1: DomainNotFoundException dnfe, 2: HostsDownException hde, 3: DomainNotLoadedException dnle);
  ; 
  ; Value directGet(1: string domain, 2: binary key)
  ;   throws (1: DomainNotFoundException dnfe, 2: DomainNotLoadedException dnle, 3: WrongHostException whe);
  ; 
  ; DomainStatus getDomainStatus(1: string domain);
  ; list<string> getDomains();
  ; Status getStatus();
  ; bool isFullyLoaded();
  ; 
  ; /*
  ;   This method will re-download the global configuration file and add any new domains
  ; */
  ; void updateAll() throws (1: InvalidConfigurationException ice);
  ; //returns whether it's updating or not
  ; bool update(1: string domain);
; { :replication 2
;   :hosts ["elephant1.server" "elephant2.server" "elephant3.server"]
;   :port 3578
;   :domains {"graph" "s3n://mybucket/elephantdb/graph"
;             "docs"  "/data/docdb"
;             }
; }


(defn- init-domain-info-map-global [fs global-config]
  (let [domain-shards (shard/shard-domains fs global-config)]
    (into {}
      (dofor [domain (keys (:domains global-config))]
          [domain (domain/init-domain-info (domain-shards domain))]
            ))))

(defn- init-domain-info-map-local [local-config]
  (throw (RuntimeException. "not implemented"))
  )


;; for version cache:
;;  1. before loading, write the global configs locally with token
;;  2. you know a domain is downloaded when the domain spec is written in the domain dir
;;  3. when starting up, only refresh those domains where a newer version exists that is less than or equal to the token (ignore global-config if token is the same as what's written locally)

;; (defn write-clj-config! [conf fs str-path]

;; if token is the same as what is local, initialize using the local data. token is the maximum version to load from hdfs
;; otherwise, read what's globally available

;; should delete any domains that don't exist in config as well 
;; returns map of domain to domain info and launches futures that will fill in the domain info
(defn- sync-data [global-config local-config token]
  (let [fs (filesystem (:hdfs-conf local-config))
        domains-info (init-domain-info-map-global global-config)
        local-dir (:local-dir local-config)
        lfs (doto (local-filesystem) (clear-dir local-dir))]
    (write-clj-config! (assoc global-config :token token) lfs (local-global-config-cache local-dir))
    (dofor [[domain remote-location] (:domains global-config)]
      (future
        (try
          (domain/set-domain-data! (domains-info domain)
            (load-domain
              fs
              local-config
              (str (path local-dir domain))
              token
              remote-location
              (domain/host-shards (domains-info domain))))
           (domain/set-domain-status!
             (domains-info domain)
             (thrift/ready-status false))
        (catch Throwable t
          (log-error t "Error when loading domain " domain)
          (domain/set-domain-status!
             (domains-info domain)
             (thrift/failed-status t))
              ))))
      domains-info ))

(defn service-handler [global-config local-config token]
  (let [domains-info (sync-data global-config local-config token)
        client (atom nil)
        #^ReentrantReadWriteLock rw-lock (mk-rw-lock)
        ret (proxy [ElephantDB$Iface Shutdownable] []
              (shutdown []
                (log-message "ElephantDB received shutdown notice...")
                (write-locked rw-lock
                  (dofor [[_ info] domains-info]
                    (domain/set-domain-status! info (thrift/shutdown-status))))
                  ;; 2. TODO close every LP (make sure to handle case where shutdown during loading)
                )

              (get [#^String domain #^bytes key]
                (.get @client domain key))

              (getString [#^String domain #^String key]
                (.getString @client domain key))

              (getInt [#^String domain key]
                (.getInt @client domain key))

              (getLong [#^String domain key]
                (.getLong @client domain key))

              (directGet [#^String domain key]
                (read-locked rw-lock
                  ;; TODO
                  ;; 1. validate domain exists and is loaded on this host
                  ;; 2. get shard for key, validate this is a valid host
                  ;; 3. find the local LP and return the data from it wrapped in a value struct
                  ))

              (getDomainStatus [#^String domain]
                ;; TODO
                )

              (getDomains []
                (keys domains-info))

              (getStatus []
                ;; TODO
                )

              (isFullyLoaded []
                ;; TODO
                )

              (updateAll []
                ;; TODO
                )

              (update [#^String domain]
                ;; TODO
                ))]
      (reset! client (client. ret (:hdfs-conf local-config) (global-config)))
      ret ))