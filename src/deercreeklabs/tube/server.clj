(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [clj-time.core :as t]
   [clj-time.format :as f]
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [org.httpkit.server :as http]
   [primitive-math])
  (:import
   (java.nio HeapByteBuffer)
   (java.security Security)))

(primitive-math/use-primitive-operators)

(defprotocol ITubeServer
  (start [this] "Start serving")
  (stop [this] "Stop serving")
  (get-conn-count [this] "Return the number of current connections"))

(deftype TubeServer [logger *conn-count starter *stopper]
  ITubeServer
  (start [this]
    (if @*stopper
      (logger :info "Tube server is already started.")
      (reset! *stopper (starter))))

  (stop [this]
    (if-let [stopper @*stopper]
      (do
        (logger :info "Attempting to stop tube server.")
        (stopper)
        (reset! *stopper nil)
        (logger :info "Tube server is stopped."))
      (logger :info "Tube server is not running.")))

  (get-conn-count [this]
    @*conn-count))

(defn make-ws-handler
  [logger on-connect on-disconnect compression-type *conn-count *conn-id]
  (fn handle-ws [req channel]
    (try
      (let [{:keys [uri remote-addr]} req
            fragment-size 65000 ;; TODO: Figure out this size
            conn-id (swap! *conn-id #(inc (int %)))
            _ (swap! *conn-count #(inc (int %)))
            sender (fn [data]
                     (http/send! channel data))
            closer #(http/close channel)
            conn (connection/connection
                  conn-id uri remote-addr on-connect sender closer fragment-size
                  compression-type false)
            on-close  (fn [reason]
                        (swap! *conn-count #(dec (int %)))
                        (connection/on-disconnect* conn 1000 reason)
                        (on-disconnect conn 1000 reason))
            on-rcv (fn [data]
                     (connection/handle-data conn data))]
        (http/on-receive channel on-rcv)
        (http/on-close channel on-close))
      (catch Exception e
        (logger :error  "Unexpected exception in handle-ws")
        (logger :error (u/ex-msg-and-stacktrace e))))))

(defn make-http-handler [logger handle-http http-timeout-ms]
  (fn [req channel]
    (au/go
      (try
        (let [ret (handle-http (update req :body #(if % (slurp %) "")))
              rsp (if-not (au/channel? ret)
                    ret
                    (let [timeout-ch (ca/timeout (or http-timeout-ms 1000))
                          [ch-ret ch] (au/alts? [ret timeout-ch])]
                      (if (= timeout-ch ch)
                        {:status 504 :body ""}
                        ch-ret)))]
          (http/send! channel (cond
                                (map? rsp)
                                rsp

                                (string? rsp)
                                {:status 200
                                 :headers {"content-type" "text/plain"}
                                 :body rsp}

                                :else
                                (let [rsp-class-name (name (class rsp))]
                                  (throw
                                   (ex-info
                                    (str "Bad return type from handle-http. "
                                         "Must be string or map. Got "
                                         rsp-class-name)
                                    (u/sym-map rsp-class-name)))))))
        (catch Exception e
          (let [msg "Unexpected exception in HTTP handler."]
            (logger :error msg)
            (logger :error (u/ex-msg-and-stacktrace e))
            (http/send! channel {:status 500 :body msg})))))))

(defn handle-http-test [req]
  (let [{:keys [body]} req]
    (if (pos? (count body))
      (string/upper-case (slurp body))
      "")))

;; TODO: Add schema to clarify opts
(defn tube-server
  ([port on-connect on-disconnect compression-type]
   (tube-server port on-connect on-disconnect compression-type {}))
  ([port on-connect on-disconnect compression-type opts]
   (let [{:keys [handle-http
                 http-timeout-ms
                 dns-cache-secs
                 logger]
          :or {dns-cache-secs 60
               logger u/noop-logger}} opts
         _ (Security/setProperty "networkaddress.cache.ttl"
                                 (str dns-cache-secs))
         *conn-count (atom 0)
         *stopper (atom nil)
         *conn-id (atom 0)
         handle-ws* (make-ws-handler logger on-connect on-disconnect
                                     compression-type *conn-count *conn-id)
         handle-http* (if handle-http
                        (make-http-handler logger handle-http http-timeout-ms)
                        (make-http-handler logger handle-http-test 1000))
         handler (fn [req]
                   (http/with-channel req channel
                     (if (http/websocket? channel)
                       (handle-ws* req channel)
                       (handle-http* req channel))))
         starter (fn []
                   (logger :info "Attempting to start tube server.")
                   (reset! *stopper (http/run-server handler (u/sym-map port)))
                   (logger :info
                           (format "Started tube server on port %s." port)))]
     (->TubeServer logger *conn-count starter *stopper))))

(defn run-test-server
  ([] (run-test-server 8080))
  ([port]
   (let [handle-http (fn [req]
                       {:status 200
                        :headers {"content-type" "text/plain"}
                        :body "Yo"})
         *server (atom nil)
         formatter (f/formatters :hour-minute-second-ms)
         timestamp #(f/unparse formatter (t/now))
         logger (fn [level msg]
                  (println
                   (str (timestamp) " "(clojure.string/upper-case (name level))
                        " " msg)))
         on-connect (fn [conn]
                      (let [conn-id (connection/get-conn-id conn)
                            uri (connection/get-uri conn)
                            remote-addr (connection/get-remote-addr conn)
                            conn-count (get-conn-count @*server)
                            on-rcv (fn [conn data]
                                     (connection/send
                                      conn (ba/reverse-byte-array data)))]
                        (logger :info (format (str "Opened conn %s on %s from "
                                                   "%s. Conn count: %s")
                                              conn-id uri remote-addr
                                              conn-count))
                        (connection/set-on-rcv conn on-rcv)))
         on-disconnect (fn [conn code reason]
                         (let [conn-id (connection/get-conn-id conn)
                               uri (connection/get-uri conn)
                               remote-addr (connection/get-remote-addr conn)
                               conn-count (get-conn-count @*server)]
                           (logger :info (format
                                          (str "Closed conn %s on %s from %s. "
                                               "Conn count: %s")
                                          conn-id uri remote-addr conn-count))))
         compression-type :smart
         opts (u/sym-map handle-http logger)
         server (tube-server port on-connect on-disconnect
                             compression-type opts)]
     (reset! *server server)
     (start server))))


(defn -main
  [& args]
  (run-test-server))
