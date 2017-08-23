# Tube Wire Protocol

The tube wire protocol is connection oriented. The connection begins
by exchanging fragment size requests, then messages.

## Initial Exchange

#### Client->Server Fragment Size Request
Sent by client, immediately after the connection is established.
An Avro-varint-encoded signed integer representing the client's desired
fragment size. 1-5 bytes in size.

#### Server->Client Fragment Size Request
Sent by server, in response to the initial client->server fragment size
requrest. An Avro-varint-encoded signed integer representing the server's desired
fragment size. 1-5 bytes in size.

## Messages
Messages may be sent by client or server at any time after the initial
message exchange.

#### Message Header
The most-significant five bits of first byte are interpreted as an
*unsigned* five-bit integer, which gives information about the
message:
0 - Message is not compressed
1 - Message is compressed using deflate (compression id 1)
2 - Reserved for future compression scheme
3 - Reserved for future compression scheme
4 - Reserved for future compression scheme
5 - Reserved for future compression scheme
6 - Reserved for future compression scheme
7 - Reserved for future compression scheme
8 - Unused
9 - Compresssion id 1 is not supported by peer
10 - Compresssion id 2 is not supported by peer
11 - Compresssion id 3 is not supported by peer
12 - Compresssion id 4 is not supported by peer
13 - Compresssion id 5 is not supported by peer
14 - Compresssion id 6 is not supported by peer
15 - Compresssion id 7 is not supported by peer
16 - Ping (Peer should immediately respond with Pong)
17 - Pong (Sent in reply to Ping, peer should not respond)
18 to 31 - Reserved for future control messages

The least-significant three bits of the first byte are interpreted as an
*unsigned* three-bit integer which represents the number of
fragments in the message. If the least-significant three bits are all zero,
the number of fragments is specified by the next 1-5 bytes, which will be
an Avro-varint-encoded signed integer. The maximum number of fragments is therefore
2^31-1.

#### Message Fragment
After a data message is started by a data message header, the data fragments
are sent. Peers must keep track of the number of fragments expected and the
number of fragments received, assembling the message when all fragments are
received.
