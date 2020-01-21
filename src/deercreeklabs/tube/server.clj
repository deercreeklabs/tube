(ns deercreeklabs.tube.server
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [primitive-math])
  (:import
   (io.netty.bootstrap ServerBootstrap)
   (io.netty.buffer ByteBuf
                    ByteBufAllocator
                    ByteBufInputStream
                    Unpooled)
   (io.netty.channel Channel
                     ChannelFuture
                     ChannelFutureListener
                     ChannelHandlerContext
                     ChannelInboundHandler
                     ChannelInboundHandlerAdapter
                     ChannelInitializer
                     ChannelOption
                     ChannelPipeline
                     SimpleChannelInboundHandler)
   (io.netty.channel.nio NioEventLoopGroup)
   (io.netty.channel.socket SocketChannel)
   (io.netty.channel.socket.nio NioServerSocketChannel)
   (io.netty.handler.codec DecoderResult)
   (io.netty.handler.codec.http DefaultFullHttpResponse
                                FullHttpRequest
                                FullHttpResponse
                                HttpHeaders
                                HttpHeaderNames
                                HttpMethod
                                HttpObjectAggregator
                                HttpRequest
                                HttpResponseStatus
                                HttpServerCodec
                                HttpServerExpectContinueHandler
                                HttpUtil
                                HttpVersion
                                QueryStringDecoder)
   (io.netty.handler.codec.http.cookie Cookie
                                       DefaultCookie
                                       ServerCookieDecoder
                                       ServerCookieEncoder)
   (io.netty.handler.codec.http.websocketx BinaryWebSocketFrame
                                           TextWebSocketFrame
                                           WebSocketFrame
                                           WebSocketServerProtocolHandler
                                           WebSocketServerProtocolHandler$HandshakeComplete)
   (io.netty.handler.codec.http.websocketx.extensions.compression
    WebSocketServerCompressionHandler)
   (io.netty.handler.ssl SslContext
                         SslContextBuilder
                         SslProvider)
   (io.netty.handler.ssl.util SelfSignedCertificate)
   (io.netty.util Attribute
                  AttributeKey
                  CharsetUtil
                  ReferenceCountUtil)
   (io.netty.util.concurrent GenericFutureListener)
   (java.io ByteArrayInputStream
            InputStream)
   (java.net HttpCookie
             InetAddress
             InetSocketAddress
             SocketAddress)
   (java.security PrivateKey
                  Security)
   (java.security.cert X509Certificate)
   (java.util Map
              Map$Entry)))

(set! *warn-on-reflection* true)
(primitive-math/use-primitive-operators)

(defn check-request [msg]
  (when-not (instance? HttpRequest msg)
    (throw (ex-info (str "Unknown request type:" (name (class msg)))
                    {:msg msg
                     :msg-class (class msg)
                     :msg-class-name (name (class msg))}))))

(defn byte-buf->byte-array [^ByteBuf buf]
  (let [ba (ba/byte-array (.readableBytes buf))]
    (.readBytes buf (bytes ba))
    ba))

(defn send-binary-data [^Channel channel ^ByteBufAllocator allocator ba]
  (let [^ByteBuf out-buf (.buffer allocator)]
    (.writeBytes out-buf (bytes ba))
    (.writeAndFlush channel (BinaryWebSocketFrame. out-buf))))

(defn ws-frame-handler [^ByteBufAllocator allocator conn logger]
  (proxy [SimpleChannelInboundHandler] []
    (channelRead0 [^ChannelHandlerContext ctx frame]
      (cond
        (instance? TextWebSocketFrame frame)
        (logger :error (str "Text frames are not supported. "
                            "You must use binary frames."))

        (instance? BinaryWebSocketFrame frame)
        (let [ba (byte-buf->byte-array (.content ^BinaryWebSocketFrame frame))]
          (connection/handle-data conn ba))

        :else
        (logger :error
                (str "Expected WebSocketFrame. Got unknown msg:" frame))))
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (logger :error
              (str "Got exception in ws-frame-handler: "
                   (u/ex-msg-and-stacktrace e)))
      (.close ctx))))

(defn ws-protocol-handler
  [on-connect on-disconnect compression-type
   ^ByteBufAllocator allocator logger *conn-id *conn-count]
  (proxy [WebSocketServerProtocolHandler] ["/" "" true
                                           (int 65536) true true]
    (userEventTriggered [^ChannelHandlerContext ctx ^Object evt]
      (when (instance? WebSocketServerProtocolHandler$HandshakeComplete evt)
        (try
          (let [^AttributeKey clj-req-ak (AttributeKey/valueOf "clj-req")
                ^Attribute clj-req-attr (.attr ctx clj-req-ak)
                clj-req (.get clj-req-attr)
                {:keys [remote-addr uri]} clj-req
                ^Channel channel (.channel ctx)
                fragment-size 65000
                conn-id (swap! *conn-id #(inc (int %)))
                _ (swap! *conn-count #(inc (int %)))
                sender (partial send-binary-data channel allocator)
                closer #(.close channel)
                conn (connection/connection
                      conn-id uri remote-addr on-connect clj-req *conn-count
                      sender closer fragment-size compression-type false)
                on-close  (fn [reason]
                            (swap! *conn-count #(dec (int %)))
                            (connection/on-disconnect* conn 1000 reason
                                                       @*conn-count)
                            (on-disconnect conn 1000 reason @*conn-count))
                close-listener (reify GenericFutureListener
                                 (operationComplete [this close-future]
                                   (let [reason "Closing"]
                                     ;; TODO: Figure out the real reason
                                     (on-close reason))))
                ^ChannelPipeline pipeline (.pipeline channel)]
            (.addListener ^ChannelFuture (.closeFuture channel)
                          close-listener)
            (.addLast pipeline "ws-frame"
                      ^SimpleChannelInboundHandler
                      (ws-frame-handler allocator conn logger)))
          (catch Exception e
            (logger :error  "Unexpected exception in ws-protocol-handler")
            (logger :error (u/ex-msg-and-stacktrace e)))))[])))

(defn get-cookies [^HttpHeaders headers]
  (let [cookie-str (.get headers HttpHeaderNames/COOKIE)]
    (when (and cookie-str (pos? (count cookie-str)))
      (let [^ServerCookieDecoder decoder (ServerCookieDecoder/LAX)
            j-cookies (.decode decoder cookie-str)]
        (reduce (fn [acc ^Cookie j-cookie]
                  (assoc acc (.name j-cookie) {:value (.value j-cookie)}))
                {} j-cookies)))))

(defn set-domain! [^Cookie cookie domain]
  (.setDomain cookie domain)
  cookie)

(defn set-path! [^Cookie cookie path]
  (.setPath cookie path)
  cookie)

(defn set-secure! [^Cookie cookie secure]
  (.setSecure cookie secure)
  cookie)

(defn set-http-only! [^Cookie cookie http-only]
  (.setHttpOnly cookie http-only)
  cookie)

(defn set-max-age! [^Cookie cookie max-age]
  (.setMaxAge cookie max-age)
  cookie)

(defn set-cookies! [^HttpHeaders headers cookie-map]
  (let [^ServerCookieEncoder encoder (ServerCookieEncoder/STRICT)
        enc-cookies (reduce-kv
                     (fn [acc cookie-name cookie-attrs]
                       (let [{:keys [value domain path
                                     secure http-only max-age]} cookie-attrs
                             cookie (cond-> (DefaultCookie. cookie-name value)
                                      ;; Use separate fns to allow type hinting
                                      domain (set-domain! domain)
                                      path (set-path! path)
                                      secure (set-secure! secure)
                                      http-only (set-http-only! http-only)
                                      max-age (set-max-age! max-age))]
                         (conj acc (.encode encoder ^Cookie cookie))))
                     [] cookie-map)]
    (doseq [enc-cookie enc-cookies]
      (.add headers HttpHeaderNames/SET_COOKIE enc-cookie))))

(defn j-headers->clj-headers [^HttpHeaders headers]
  (reduce (fn [acc [k v]]
            (assoc acc (str/lower-case k) v))
          {} (iterator-seq (.iteratorAsString headers))))

(defn parse-host [host]
  (let [[name-part port-part] (when host (str/split host #":"))
        server-port (if (seq port-part)
                      (Integer/parseInt port-part)
                      80)]
    {:server-port server-port
     :server-name name-part}))

(defn http-method [^FullHttpRequest req]
  (let [^HttpMethod j-method (.method req)]
    (cond
      (.equals j-method HttpMethod/GET) :get
      (.equals j-method HttpMethod/POST) :post
      (.equals j-method HttpMethod/PUT) :put
      (.equals j-method HttpMethod/HEAD) :head
      (.equals j-method HttpMethod/DELETE) :delete
      (.equals j-method HttpMethod/PATCH) :patch
      (.equals j-method HttpMethod/TRACE) :trace
      (.equals j-method HttpMethod/CONNECT) :connect)))

(defn j-req->clj-req
  [^FullHttpRequest req ^Channel channel j-headers cookies ssl?]
  (let [^QueryStringDecoder qsd (QueryStringDecoder. ^String (.uri req))
        clj-headers (j-headers->clj-headers j-headers)
        {:keys [server-port server-name]} (parse-host (clj-headers "host"))
        ^InetSocketAddress remote-addr (.remoteAddress channel)]
    {:server-port server-port
     :server-name server-name
     :remote-addr (.getHostAddress ^InetAddress (.getAddress remote-addr))
     :uri (.path qsd)
     :query-string (.rawQuery qsd)
     :scheme (if ssl? :https :http)
     :request-method (http-method req)
     :protocol (.text ^HttpVersion (.protocolVersion req))
     :cookies cookies
     :headers clj-headers
     :body (ByteBufInputStream. (.content req))}))

(defn clj-rsp->j-rsp [^FullHttpRequest req clj-rsp]
  (let [{:keys [body cookies headers status]
         :or {status 200}} clj-rsp
        ^ByteBuf buf (Unpooled/copiedBuffer (str body) (CharsetUtil/UTF_8))
        rsp (DefaultFullHttpResponse. (.protocolVersion req)
                                      (HttpResponseStatus/valueOf status)
                                      buf)
        rsp-headers (.headers ^DefaultFullHttpResponse rsp)]
    (doseq [[header-name header-value] headers]
      (.set ^HttpHeaders rsp-headers (str header-name) header-value))
    (set-cookies! rsp-headers cookies)
    (HttpUtil/setContentLength rsp (.readableBytes buf))
    rsp))

(defn send-http-rsp [^ChannelHandlerContext ctx
                     ^FullHttpRequest req
                     ^FullHttpResponse rsp]
  (let [^HttpResponseStatus rsp-status (.status rsp)
        keep-alive? (and (HttpUtil/isKeepAlive req)
                         (= 200 (.code rsp-status)))
        _ (HttpUtil/setKeepAlive rsp keep-alive?)
        ^ChannelFuture f (.writeAndFlush ctx rsp)]
    (when-not keep-alive?
      (.addListener f ChannelFutureListener/CLOSE))))

(defn upgrade? [^HttpHeaders headers]
  (let [conn (.get headers HttpHeaderNames/CONNECTION)
        upgrade (.get headers HttpHeaderNames/UPGRADE)]
    (and conn
         upgrade
         (re-find #"(?i)upgrade" conn)
         (re-find #"(?i)websocket" upgrade))))

(defn http-handler
  [^ByteBufAllocator allocator handle-http timeout-ms ssl? logger]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [^ChannelHandlerContext ctx ^FullHttpRequest req]
      (if-not (instance? FullHttpRequest req)
        (.fireChannelRead ctx req)
        (if (not (.isSuccess ^DecoderResult (.decoderResult req)))
          (send-http-rsp ctx req
                         (DefaultFullHttpResponse.
                          (.protocolVersion req)
                          HttpResponseStatus/BAD_REQUEST
                          (.buffer allocator 0)))
          (let [^HttpHeaders req-headers (.headers req)
                channel (.channel ctx)
                cookies (get-cookies req-headers)
                clj-req (j-req->clj-req req channel req-headers cookies ssl?)
                ^AttributeKey clj-req-ak (AttributeKey/valueOf "clj-req")
                ^Attribute clj-req-attr (.attr ctx clj-req-ak)]
            (.set clj-req-attr clj-req)
            (if (upgrade? req-headers)
              (.fireChannelRead ctx req)
              (au/go
                (try
                  (let [ret (handle-http clj-req)
                        clj-rsp (if-not (au/channel? ret)
                                  ret
                                  (let [timeout-ch (ca/timeout (or timeout-ms
                                                                   1000))
                                        [ch-ret ch] (au/alts? [ret timeout-ch])]
                                    (if (= timeout-ch ch)
                                      {:status 504 :body ""}
                                      ch-ret)))
                        _ (when-not (map? clj-rsp)
                            (throw
                             (ex-info
                              (str "`handle-http` did not return a map. Got: "
                                   clj-rsp)
                              {:ret clj-rsp
                               :ret-class (class clj-rsp)})))
                        rsp (clj-rsp->j-rsp req clj-rsp)]
                    (send-http-rsp ctx req rsp))
                  (catch Exception e
                    (logger :error
                            (str "Got exception in http-handler go block:"
                                 (u/ex-msg-and-stacktrace e))))
                  (finally
                    (ReferenceCountUtil/release req)))))))))
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (logger :error
              (str "Got exception in http-handler: "
                   (u/ex-msg-and-stacktrace e)))
      (.close ctx))))

(defn initializer
  [handle-http http-handler-timeout-ms ssl-ctx ws-on-connect ws-on-disconnect
   ws-compression-type *conn-id *conn-count logger]
  (proxy [ChannelInitializer] []
    (initChannel [^SocketChannel socket-channel]
      (let [^ByteBufAllocator allocator (.alloc socket-channel)
            ^ChannelPipeline pipeline (.pipeline socket-channel)]
        (when ssl-ctx
          (.addLast pipeline "ssl" (.newHandler ^SslContext ssl-ctx allocator)))
        (doto pipeline
          (.addLast "http" (HttpServerCodec.))
          (.addLast "http-oa" (HttpObjectAggregator. 65536))
          (.addLast "ws-comp" (WebSocketServerCompressionHandler.))
          (.addLast "http-req" ^SimpleChannelInboundHandler
                    (http-handler allocator handle-http http-handler-timeout-ms
                                  (boolean ssl-ctx) logger))
          (.addLast "ws-handler"
                    ^WebSocketServerProtocolHandler
                    (ws-protocol-handler
                     ws-on-connect ws-on-disconnect ws-compression-type
                     allocator logger *conn-id *conn-count)))))
    (exceptionCaught [^ChannelHandlerContext ctx e]
      (logger :error
              (str "Got exception in channel initializer:"
                   (u/ex-msg-and-stacktrace e)))
      (.close ctx))))

(defn str->input-stream [s]
  (ByteArrayInputStream. (.getBytes ^String s)))

(defn make-ssl-ctx [cert-str pkey-str]
  (let [cert-stream (str->input-stream cert-str)
        pkey-stream (str->input-stream pkey-str)]
    (-> (SslContextBuilder/forServer ^InputStream cert-stream
                                     ^InputStream pkey-stream)
        (.sslProvider SslProvider/OPENSSL)
        (.build))))

(defn make-ssl-ctx-self-signed []
  (let [^SelfSignedCertificate ssc (SelfSignedCertificate.)
        pkey (.key ssc)
        ^X509Certificate cert (.cert ssc)
        cert-array (into-array X509Certificate [cert])
        builder (SslContextBuilder/forServer
                 ^PrivateKey pkey
                 ^"[Ljava.security.cert.X509Certificate;" cert-array)]
    (.build ^SslContextBuilder builder)))

(def valid-compression-types #{:smart :none :deflate})

(defn check-config [config]
  (let [{:keys [certificate-str
                dns-cache-secs
                handle-http
                http-timeout-ms
                logger
                private-key-str
                use-self-signed-certificate?
                ws-on-connect
                ws-on-disconnect
                ws-compression-type]} config]
    (when certificate-str
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` parameter must be a string. "
                     "Got: " certificate-str)
                (u/sym-map certificate-str)))())
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when private-key-str
      (when-not (string? private-key-str)
        (throw (ex-info
                (str "`:private-key-str` parameter must be a string. "
                     "Got: " private-key-str)
                (u/sym-map private-key-str)))())
      (when-not (string? certificate-str)
        (throw (ex-info
                (str "`:certificate-str` was given, but not `:private-key-str`."
                     " Both are required to use an SSL certificate.")
                (u/sym-map certificate-str private-key-str)))))
    (when (and dns-cache-secs (not (integer? dns-cache-secs)))
      (throw (ex-info
              (str "`:dns-cache-secs` parameter must be an integer. Got: `"
                   dns-cache-secs "`.")
              (u/sym-map dns-cache-secs))))
    (when (and handle-http (not (ifn? handle-http)))
      (throw (ex-info
              (str "`:handle-http` option must be a function. Got: `"
                   handle-http "`.")
              (u/sym-map handle-http))))
    (when (and logger (not (ifn? logger)))
      (throw (ex-info
              (str "`:logger` option must be a function. Got: `" logger "`.")
              (u/sym-map logger))))
    (when-not ws-on-connect
      (throw (ex-info
              "You must provide a :ws-on-connect fn in the tube-server config."
              config)))
    (when-not (ifn? ws-on-connect)
      (throw (ex-info
              (str "`:ws-on-connect` value must be a function. Got: `"
                   ws-on-connect "`.")
              (u/sym-map ws-on-connect))))
    (when (and ws-on-disconnect (not (ifn? ws-on-disconnect)))
      (throw (ex-info
              (str "`:ws-on-disconnect` value must be a function. Got: `"
                   ws-on-disconnect "`.")
              (u/sym-map ws-on-disconnect))))
    (when (and ws-compression-type
               (not (valid-compression-types ws-compression-type)))
      (throw (ex-info
              (str "`:ws-compression-type` must be one of "
                   valid-compression-types ". Got `" ws-compression-type "`.")
              (u/sym-map ws-compression-type)))))  )

(defn tube-server
  ([port config]
   (when-not (int? port)
     (throw (ex-info (str "`port` parameter must be an integer. Got: " port)
                     {:port port})))
   (check-config config)
   (let [{:keys [certificate-str
                 compression-type
                 dns-cache-secs
                 handle-http
                 http-timeout-ms
                 logger
                 private-key-str
                 use-self-signed-certificate?
                 ws-compression-type
                 ws-on-connect
                 ws-on-disconnect]
          :or {dns-cache-secs 60
               http-timeout-ms 30000
               logger u/println-logger
               ws-compression-type :smart
               ws-on-disconnect (constantly nil)}} config
         _ (Security/setProperty "networkaddress.cache.ttl"
                                 (str dns-cache-secs))
         ssl-ctx (cond
                   (and certificate-str private-key-str)
                   (make-ssl-ctx certificate-str private-key-str)

                   use-self-signed-certificate?
                   (make-ssl-ctx-self-signed))
         boss-group (NioEventLoopGroup.)
         worker-group (NioEventLoopGroup.)
         do-shutdown (fn []
                       (.shutdownGracefully worker-group)
                       (.shutdownGracefully boss-group)
                       (constantly nil))
         *conn-id (atom 0)
         *conn-count (atom 0)]
     (try
       (logger :info "Starting server...")
       (let [b (doto (ServerBootstrap.)
                 (.group boss-group worker-group)
                 (.channel NioServerSocketChannel)
                 (.childHandler (initializer
                                 handle-http http-timeout-ms ssl-ctx
                                 ws-on-connect ws-on-disconnect
                                 ws-compression-type *conn-id *conn-count
                                 logger))
                 (.option ChannelOption/SO_BACKLOG (int 128))
                 (.childOption ChannelOption/SO_KEEPALIVE true))
             ^ChannelFuture f (-> (.bind ^ServerBootstrap b (int port))
                                  (.sync))
             ^Channel channel (.channel f)
             stop-server (fn []
                           (.close channel)
                           nil)
             close-listener (reify GenericFutureListener
                              (operationComplete [this f]
                                (logger :info "Shutting down server")
                                (do-shutdown)))]
         (.addListener ^ChannelFuture (.closeFuture channel) close-listener)
         (logger :info (str "Started server on port " port "."))
         stop-server)
       (catch Exception e
         (logger :error (str "Got error in tube-server startup:"
                             (u/ex-msg-and-stacktrace e)))
         (do-shutdown))))))

;;;;;;;;;;;;;;;;;;;; Test stuff ;;;;;;;;;;;;;;;;;;;;

(defn handle-http [req]
  (au/go
    {:status 200
     :headers {"content-type" "text/plain; charset=UTF-8"}
     :cookies {"c1" {:value "new-test-value"
                     :http-only true
                     :max-age (* 3600 1)}
               "c2" {:value "v2"
                     :max-age 30}}
     :body "Yo"}))

(defn on-connect [logger conn conn-req conn-count]
  (let [conn-id (connection/get-conn-id conn)
        uri (connection/get-uri conn)
        remote-addr (connection/get-remote-addr conn)
        on-rcv (fn [conn data]
                 (connection/send
                  conn (ba/reverse-byte-array data)))]
    (logger :info (format (str "Opened conn %s on %s from "
                               "%s. Conn count: %s")
                          conn-id uri remote-addr
                          conn-count))
    (connection/set-on-rcv! conn on-rcv)))

(defn on-disconnect [logger conn code reason conn-count]
  (let [conn-id (connection/get-conn-id conn)
        uri (connection/get-uri conn)
        remote-addr (connection/get-remote-addr conn)]
    (logger :info (format
                   (str "Closed conn %s on %s from %s. "
                        "Conn count: %s")
                   conn-id uri remote-addr conn-count))))

(defn run-normal-test-server
  ([] (run-normal-test-server 8080))
  ([port]
   (let [config {:handle-http handle-http
                 :logger u/println-logger
                 :ws-on-connect (partial on-connect u/println-logger)
                 :ws-on-disconnect (partial on-disconnect u/println-logger)}]
     (tube-server port config))))

(defn run-ssl-test-server
  ([] (run-ssl-test-server 8443))
  ([port]
   (let [config {:handle-http handle-http
                 :logger u/println-logger
                 :use-self-signed-certificate? true
                 :ws-on-connect (partial on-connect u/println-logger)
                 :ws-on-disconnect (partial on-disconnect u/println-logger)}]
     (tube-server port config))))

(defn run-test-servers []
  (let [stop-normal-server (run-normal-test-server)
        stop-ssl-test-server (run-ssl-test-server)]
    (fn stop-servers []
      (stop-normal-server)
      (stop-ssl-test-server))))

(defn -main
  [& args]
  (run-normal-test-server)
  (run-ssl-test-server))
