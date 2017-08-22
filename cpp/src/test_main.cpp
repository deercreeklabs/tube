#include "tube_server.h"
#include <iostream>

using namespace std;

void on_rcv (conn_id_t conn_id, void *data) {
    cout << "got data on conn_id " << conn_id << endl;
}

void on_connect (conn_id_t conn_id, cstr peer_name) {
    cout << "conn " << conn_id << " to " << peer_name;
    cout << " connected" << endl;
}

void on_disconnect (conn_id_t conn_id, cstr peer_name) {
    cout << "conn " << conn_id << " to " << peer_name;
    cout << " disconnected" << endl;
}

int main (int argc, char *argv[]) {
    int port = 8080;
    cout << "Starting server on " << port << "." << endl;
    TubeServer ts("key", "cert", port,
                  on_rcv, on_connect, on_disconnect);
    cout << "conn count: " << ts.get_conn_count() << endl;
}
