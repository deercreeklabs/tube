#include "tube_server.h"

TubeServer::TubeServer(const char *sslKey, const char *sslCert, int port,
                       on_rcv_fn_t on_rcv_fn) {
    count = 123;
    on_rcv_fn("ABC", (void*)"data");

}

int TubeServer::get_conn_count() {
    return count;
}
