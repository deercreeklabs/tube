#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include <uWS/uWS.h>  // Needs to be first for some reason

#include "connection.h"
#include "utils.h"
#include <cstdint>
#include <unordered_map>

enum CompressionType {
    NONE,
    SMART,
    DEFLATE
};


class TubeServer
{
private:
    uWS::Hub hub;
    CompressionType compression_type;
    conn_id_t next_conn_id;
    std::unordered_map<conn_id_t, ws_t*> conn_id_to_ws;
    std::unordered_map<ws_t*, Connection> ws_to_conn;
    void sendControlCode(ws_t *ws, uint_fast8_t code);
    void sendPing(ws_t *ws) { sendControlCode(ws, 16); }
    void sendPong(ws_t *ws) { sendControlCode(ws, 17); }
    void onMessage(ws_t *ws, char *message,
                   size_t length, uWS::OpCode opCode, on_rcv_fn_t on_rcv_fn);
    void handleReadyStateMessage(ws_t *ws, char *message, size_t length,
                                 uWS::OpCode opCode, Connection& conn,
                                 on_rcv_fn_t on_rcv_fn,
                                 bool is_cur_msg_compressed);

public:
    TubeServer(const char *sslKey, const char *sslCert, uint32_t port,
               CompressionType compression_type,
               on_rcv_fn_t on_rcv_fn,
               on_connect_fn_t on_connect_fn,
               on_disconnect_fn_t on_disconnect_fn
        );
    void serve() { hub.run(); }
    void close_conn(conn_id_t conn_id);
    void send(conn_id_t conn_id, const char* data, uint32_t length);
    uint32_t getConnCount() { return ws_to_conn.size(); };
};


#endif // TUBE_SERVER_H
