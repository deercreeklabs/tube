#ifndef TUBE_SERVER_H
#define TUBE_SERVER_H

typedef const char *cstr;

typedef void on_rcv_fn_t (cstr conn_id, void *data);

class TubeServer
{
    int count;

public:
    TubeServer(const char *sslKey, const char *sslCert, int port,
               on_rcv_fn_t on_rcv_fn);
    int send(cstr conn_id, void *data);
    int close(cstr conn_id);
    int get_conn_count();
};

#endif
