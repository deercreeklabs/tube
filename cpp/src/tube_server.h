#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

#include "tube_connection.h"
#include "server_wss.hpp"
#include "utils.h"
#include <cstdint>
#include <unordered_map>

class TubeServer
{
private:
    WssServer server;
    CompressionType compression_type;
    on_rcv_fn_t& on_rcv_fn;
    on_connect_fn_t& on_connect_fn;
    on_disconnect_fn_t& on_disconnect_fn;
    std::mutex bookkeeping_mtx;
    conn_id_t next_conn_id;
    std::unordered_map<
        conn_id_t,
        std::shared_ptr<TubeConnection>> conn_id_to_tc;

public:
    TubeServer(const std::string& ssl_cert_filename,
               const std::string& ssl_key_filename,
               uint32_t port,
               CompressionType compression_type,
               on_rcv_fn_t& on_rcv_fn,
               on_connect_fn_t& on_connect_fn,
               on_disconnect_fn_t& on_disconnect_fn);
    void serve();
    void close_conn(conn_id_t conn_id);
    void send(conn_id_t conn_id, std::string bytes);
    uint32_t getConnCount() { return conn_id_to_tc.size(); };
};


#endif // TUBE_SERVER_H
