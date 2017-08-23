#include "tube_server.h"
#include "utils.h"
#include <cstdio>
#include <cstdint>
#include <iostream>
#include <string>


using namespace std;


void on_rcv (ws_t *ws, void *data, uint32_t length) {
    cout << "got data on ws. " << endl;
}

void on_connect (ws_t *ws) {
    cout << "on_connect " << endl;
}

void on_disconnect (ws_t *ws, const char *reason) {
    cout << "conn disconnected. Reason: " << reason << endl;
}

void test_encode_decode() {
    int32_t data[] = {0, -1, 1, 100, -100, 1000, 10000};
    char expected[][4] = { {0, 0, 0, 0},
                           {1, 0, 0, 0},
                           {2, 0, 0, 0},
                           {-56, 1, 0, 0},
                           {-57, 1, 0, 0},
                           {-48, 15, 0, 0},
                           {-96, -100, 1, 0} };
    char encoded[][4] = { {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0},
                          {0, 0, 0, 0} };
    uint_fast8_t num_cases = sizeof(data) / sizeof(int32_t);

    cout << "Testing varint-zz encoding:" << endl;
    for(uint_fast8_t i=0; i < num_cases; ++i) {
        uint_fast8_t len = encode_int(data[i], encoded[i]);
        bool success = true;
        for(uint_fast8_t j=0; j<len; ++j) {
            if(expected[i][j] != encoded[i][j]) {
                success = false;
                break;
            }
        }
        cout << (success ? "." : "E");
    }
    cout << endl;

    cout << "Testing varint-zz decoding:" << endl;
    for(uint_fast8_t i=0; i < num_cases; ++i) {
        int32_t n = decode_int(encoded[i]);
        if(n == data[i]) {
            cout << ".";
        } else {
            cout << "Error:" << endl;
            cout << "  Expected: " << data[i] << endl;
            cout << "  Got: " << n << endl;
        }
    }
    cout << endl;

}

void run_unit_tests() {
    test_encode_decode();
}

void run_server() {
    uint32_t port = 8080;
    cout << "Starting server on " << port << "." << endl;
    TubeServer ts("key", "cert", port,
                  on_rcv, on_connect, on_disconnect);
}

int main (int argc, char *argv[]) {
    run_unit_tests();
    //run_server();
}
