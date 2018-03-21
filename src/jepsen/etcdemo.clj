(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as v]
            [jepsen [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 2379))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn db
  "Etcd at a particular version"
  [version]
  (reify db/DB
         (setup! [db test node]
                (let [url (str "https://storage.googleapis.com/etcd/" version
                               "/etcd-" version "-linux-amd64.tar.gz")]
                  (c/su
                    (cu/install-archive! url dir)
                    (cu/start-daemon!
                     {:logfile logfile
                      :pidfile pidfile
                      :chdir dir}
                     binary
                     :--log-output                   :stderr
                     :--name                         (name node)
                     :--listen-peer-urls             (peer-url   node)
                     :--listen-client-urls           (client-url node)
                     :--advertise-client-urls        (client-url node)
                     :--initial-cluster-state        :new
                     :--initial-advertise-peer-urls  (peer-url node)
                     :--initial-cluster              (initial-cluster test))))

                    (comment this should loop and wait for the db to be alive but we're lazy)

                    (Thread/sleep 10000))
         (teardown! [db test node]
                    (info node "tearing down etcd")
                    (cu/stop-daemon! binary pidfile)
                    (c/su (c/exec :rm :-rf dir)))

         db/LogFiles
         (log-files [db test node]
                    [logfile])))

(defn r   [test process] {:type :invoke, :f :read, :value nil})
(defn w   [test process] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [test process] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})


(comment "in open!, should use a timeout. can use this if not provided by client lib: (util/timeout 5000 :default (can write stuff here))")

(defrecord Client [conn]
  client/Client

  (open! [this test node]
         (assoc this :conn (v/connect (client-url node)
                                      {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op])

  (teardown! [_ test])

  (close! [_ test]))
  (comment "close connections here if the db library uses connections")

(defn etcd-test
    "Given an options map from the command line runner (e.g. :nodes, :ssh,
    :concurrency, ...), constructs a test map."
    [opts]
    (merge tests/noop-test
           opts
           {:os debian/os
            :db (db "v3.1.5")
            :client (Client. nil)
            :generator (->> r
                            (gen/stagger 1)
                            (gen/nemesis nil)
                            (gen/time-limit 10))}))

(comment "(gen/nemesis nil) disables the nemesis")

(defn -main
    "Handles command line arguments. Can either run a test, or a web server for
    browsing results."
    [& args]
    (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                     (cli/serve-cmd))
              args))