#include "tube_server.h"
#include <iostream>

using namespace std;

TubeServer::TubeServer(const char *sslKey, const char *sslCert, int port,
                       on_rcv_fn_t on_rcv_fn,
                       on_connect_fn_t on_connect_fn,
                       on_disconnect_fn_t on_disconnect_fn)
    : connection_count(0), next_conn_id(1) {

    hub.onConnection(
        [this](uWS::WebSocket<uWS::SERVER> *ws, uWS::HttpRequest req) {
            cout << "onConnection:\n  conn_id: " << this->next_conn_id << endl;
            conn_id_to_ws.insert({this->next_conn_id++, ws});
        });
    hub.listen(port);
    hub.run();
}

int TubeServer::get_conn_count() {
    return connection_count;
}
