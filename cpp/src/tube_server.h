#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include <unordered_map>
#include <uWS/uWS.h>

typedef const char *cstr;
typedef int conn_id_t;

typedef void on_rcv_fn_t (conn_id_t conn_id, void *data);
typedef void on_connect_fn_t (conn_id_t conn_id, cstr peer_name);
typedef void on_disconnect_fn_t (conn_id_t conn_id, cstr peer_name);

class TubeServer
{
private:
    uWS::Hub hub;
    int connection_count;
    conn_id_t next_conn_id;
    std::unordered_map<conn_id_t, uWS::WebSocket<uWS::SERVER> *> conn_id_to_ws;

public:
    TubeServer(const char *sslKey, const char *sslCert, int port,
               on_rcv_fn_t on_rcv_fn,
               on_connect_fn_t on_connect_fn,
               on_disconnect_fn_t on_disconnect_fn);
    int send(conn_id_t conn_id, void *data);
    int close(conn_id_t conn_id);
    int get_conn_count();
};

#endif
