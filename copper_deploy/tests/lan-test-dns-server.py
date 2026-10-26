#!/usr/bin/env python3
"""Minimal UDP A-record server used only by isolated M5 LAN acceptance."""

from __future__ import annotations

import argparse
import socket
import struct


def question_end(message: bytes) -> int:
    offset = 12
    while offset < len(message):
        length = message[offset]
        offset += 1
        if length == 0:
            break
        if length > 63 or offset + length > len(message):
            raise ValueError("invalid question")
        offset += length
    if offset + 4 > len(message):
        raise ValueError("truncated question")
    return offset + 4


def question_name(message: bytes) -> str:
    labels: list[str] = []
    offset = 12
    while offset < len(message):
        length = message[offset]
        offset += 1
        if length == 0:
            return ".".join(labels).lower()
        if length > 63 or offset + length > len(message):
            raise ValueError("invalid name")
        labels.append(message[offset:offset + length].decode("ascii"))
        offset += length
    raise ValueError("truncated name")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen", required=True)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--answer", required=True)
    args = parser.parse_args()
    answer = socket.inet_aton(args.answer)
    wanted = args.domain.lower().rstrip(".")
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        server.bind((args.listen, 53))
        while True:
            message, client = server.recvfrom(4096)
            try:
                if len(message) < 12 or question_name(message) != wanted:
                    continue
                end = question_end(message)
                transaction = message[:2]
                response = transaction + struct.pack("!HHHHH", 0x8180, 1, 1, 0, 0)
                response += message[12:end]
                response += b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 60, 4) + answer
                server.sendto(response, client)
            except (UnicodeError, ValueError, struct.error):
                continue


if __name__ == "__main__":
    main()
