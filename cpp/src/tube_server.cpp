#include "tube_server.h"
#include <iostream>

using namespace std;

TubeServer::TubeServer(const char *sslKey, const char *sslCert, int port,
                       on_rcv_fn_t on_rcv_fn,
                       on_connect_fn_t on_connect_fn,
                       on_disconnect_fn_t on_disconnect_fn)
    : connection_count(0) {

    hub.onConnection(
        [this, on_connect_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, uWS::HttpRequest req) {
            this->connection_count++;
            cout << "onConnection:\n  connection_count: ";
            cout << connection_count << endl;
            on_connect_fn(ws);
        });
    hub.onDisconnection(
        [this, on_disconnect_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, int code,
         char *message, size_t length) {
            this->connection_count--;
            string msg(message, length);
            cout << "onDisconnection. Close code: " << code;
            cout << " msg: " << message << endl;
            on_disconnect_fn(ws, message);
        });
    hub.onMessage(
        [on_rcv_fn]
        (uWS::WebSocket<uWS::SERVER> *ws, char *message, size_t length,
         uWS::OpCode opCode) {
            on_rcv_fn(ws, (void*)message, length);
        });
    hub.listen(port);
    hub.run();
}

void TubeServer::send(ws_t *ws, void *data, int length) {
    ws->send((char*)data, length, uWS::OpCode::BINARY);
};
