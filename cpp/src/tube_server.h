#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include <unordered_map>
#include <uWS/uWS.h>

typedef const char *cstr;
typedef uWS::WebSocket<uWS::SERVER> ws_t;

typedef void on_rcv_fn_t (ws_t *ws, void *data, int length);
typedef void on_connect_fn_t (ws_t *ws);
typedef void on_disconnect_fn_t (ws_t *ws, const char *reason);

class TubeServer
{
private:
    uWS::Hub hub;
    int connection_count;

public:
    TubeServer(const char *sslKey, const char *sslCert, int port,
               on_rcv_fn_t on_rcv_fn,
               on_connect_fn_t on_connect_fn,
               on_disconnect_fn_t on_disconnect_fn);
    void send(ws_t *ws, void *data, int length);
    void close(ws_t *ws) { ws->close(1000, "Explicit close", 14); };
    int get_conn_count() { return connection_count; };
};


#endif
