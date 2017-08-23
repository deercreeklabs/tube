#ifndef CONNECTION_H
#define CONNECTION_H

#include <cstdint>

enum State {
    CONNECTED,
    FRAG_SIZE_NEGOTIATED,
    READY
};

class Connection
{
private:
    State state;
    int32_t peer_fragment_size;

public:
    Connection() { state = CONNECTED; }
    uint8_t getState() { return state; }
};


#endif // CONNECTION_H
