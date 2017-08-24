#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include <uWS/uWS.h>  // Needs to be first for some reason

#include "connection.h"
#include <cstdint>
#include <unordered_map>


typedef const char *cstr;
typedef uWS::WebSocket<uWS::SERVER> ws_t;

typedef void on_rcv_fn_t (ws_t *ws, const char *data, uint32_t length);
typedef void on_connect_fn_t (ws_t *ws);
typedef void on_disconnect_fn_t (ws_t *ws, const char *reason);

class TubeServer
{
private:
    uWS::Hub hub;
    std::unordered_map<uWS::WebSocket<uWS::SERVER> *, Connection> conns;
    void sendControlCode(ws_t *ws, uint_fast8_t code);
    void sendPing(ws_t *ws) { sendControlCode(ws, 16); }
    void sendPong(ws_t *ws) { sendControlCode(ws, 17); }

public:
    TubeServer(const char *sslKey, const char *sslCert, uint32_t port,
               on_rcv_fn_t on_rcv_fn,
               on_connect_fn_t on_connect_fn,
               on_disconnect_fn_t on_disconnect_fn);
    void close(ws_t *ws) { ws->close(1000, "Explicit close", 14); };
    uint32_t getConnCount() { return conns.size(); };
};


#endif // TUBE_SERVER_H
