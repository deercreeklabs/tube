#include "tube_server.h"
#include <iostream>

using namespace std;

void on_rcv (ws_t *ws, void *data, int length) {
    cout << "got data on ws. " << endl;
}

void on_connect (ws_t *ws) {
    cout << "on_connect " << endl;
}

void on_disconnect (ws_t *ws, const char *reason) {
    cout << "conn disconnected. Reason: " << reason << endl;
}

int main (int argc, char *argv[]) {
    int port = 8080;
    cout << "Starting server on " << port << "." << endl;
    TubeServer ts("key", "cert", port,
                  on_rcv, on_connect, on_disconnect);
    cout << "conn count: " << ts.get_conn_count() << endl;
}
