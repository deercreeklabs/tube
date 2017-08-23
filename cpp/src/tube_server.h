#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include "connection.h"
#include <cstdint>
#include <unordered_map>
#include <uWS/uWS.h>

typedef const char *cstr;
typedef uWS::WebSocket<uWS::SERVER> ws_t;

typedef void on_rcv_fn_t (ws_t *ws, void *data, uint32_t length);
typedef void on_connect_fn_t (ws_t *ws);
typedef void on_disconnect_fn_t (ws_t *ws, const char *reason);

class TubeServer
{
private:
    uWS::Hub hub;
    std::unordered_map<uWS::WebSocket<uWS::SERVER> *, Connection> conns;

public:
    TubeServer(const char *sslKey, const char *sslCert, uint32_t port,
               on_rcv_fn_t on_rcv_fn,
               on_connect_fn_t on_connect_fn,
               on_disconnect_fn_t on_disconnect_fn);
    void send(ws_t *ws, void *data, uint32_t length);
    void close(ws_t *ws) { ws->close(1000, "Explicit close", 14); };
    uint32_t getConnCount() { return conns.size(); };
};


#endif // TUBE_SERVER_H
