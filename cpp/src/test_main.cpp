#include "tube_server.h"
#include <iostream>

using namespace std;

void on_rcv (cstr conn_id, void *data) {
    cout << "got data on conn_id " << conn_id << endl;
}

int main (int argc, char *argv[]) {
    TubeServer ts = TubeServer("key", "cert", 8080, on_rcv);
    cout << "conn count: " << ts.get_conn_count() << endl;
}
