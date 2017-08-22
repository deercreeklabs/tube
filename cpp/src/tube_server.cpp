#include "tube_server.h"

TubeServer::TubeServer(const char *sslKey, const char *sslCert, int port,
                       on_rcv_fn_t on_rcv_fn,
                       on_connect_fn_t on_connect_fn,
                       on_disconnect_fn_t on_disconnect_fn)
    : connection_count(0) {

}

int TubeServer::get_conn_count() {
    return connection_count;
}
