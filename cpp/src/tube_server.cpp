#include "tube_connection.h"
#include "server_wss.hpp"
#include "tube_server.h"
#include "utils.h"
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

using namespace std;


conn_id_t connToId(shared_ptr<WssServer::Connection> connection) {
    std::ostringstream buffer;
    buffer << connection->remote_endpoint_address << ":";
    buffer << connection->remote_endpoint_port;
    return buffer.str();
}

TubeServer::TubeServer(const string& ssl_cert_filename,
                       const string& ssl_key_filename,
                       uint32_t port,
                       CompressionType compression_type,
                       on_rcv_fn_t& on_rcv_fn,
                       on_connect_fn_t& on_connect_fn,
                       on_disconnect_fn_t& on_disconnect_fn)
    : server(ssl_cert_filename, ssl_key_filename),
      compression_type(compression_type),
      on_rcv_fn(on_rcv_fn),
      on_connect_fn(on_connect_fn),
      on_disconnect_fn(on_disconnect_fn) {
    server.config.port = port;
    auto &endpoint = server.endpoint[".*"];

    endpoint.on_open = [&](shared_ptr<WssServer::Connection> connection) {
        auto conn_id = connToId(connection);
        cout << "Server: Got connection to " << conn_id << endl;
        auto tc = make_shared<TubeConnection>(*this, conn_id, connection,
                                              compression_type, on_rcv_fn);
        bookkeeping_mtx.lock();
        conn_id_to_tc.insert({conn_id, tc});
        bookkeeping_mtx.unlock();
        on_connect_fn(*this, conn_id, connection->path,
                      connection->remote_endpoint_address);
    };

    endpoint.on_message = [&](shared_ptr<WssServer::Connection> connection,
                              shared_ptr<WssServer::Message> message) {
        auto conn_id = connToId(connection);
        cout << "(" << __FILE__ << ":" << __LINE__;
        cout << ") Server: Message received from " << conn_id << endl;
        auto tc = conn_id_to_tc[conn_id];
        tc->handleOnRcv(message->string());
    };

    // See RFC 6455 7.4.1. for status codes
    endpoint.on_close = [&](shared_ptr<WssServer::Connection> connection,
                            int status,
                            const string& reason) {
        cout << "Server: Closed connection " << connection.get();
        cout << " with status code " << status << endl;
        auto conn_id = connToId(connection);
        bookkeeping_mtx.lock();
        conn_id_to_tc.erase(conn_id);
        bookkeeping_mtx.unlock();
        on_disconnect_fn(*this, conn_id, status, reason);
    };

    endpoint.on_error = [&](shared_ptr<WssServer::Connection> connection,
                            const SimpleWeb::error_code &ec) {
        auto conn_id = connToId(connection);
        cout << "Server: Error in connection " << conn_id;
        cout << ". Error: " << ec << ", error message: ";
        cout << ec.message() << endl;
        // TODO: handleClose
    };
      }

void TubeServer::send(conn_id_t conn_id, std::string bytes) {
    auto tc = conn_id_to_tc[conn_id];
    tc->send(bytes);
}

// void TubeServer::serve() {
//     server.start();
// }

void TubeServer::serve() {
    thread server_thread([this]() {
            server.start();
        });
    server_thread.join();
}

void TubeServer::close_conn(conn_id_t conn_id) {
    auto tc = conn_id_to_tc[conn_id];
    tc->close(1000);
}
