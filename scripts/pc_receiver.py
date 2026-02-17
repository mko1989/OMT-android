#!/usr/bin/env python3
"""
Connect to OMT Camera app on Android and receive the raw frame stream.
Usage: python3 pc_receiver.py <phone_ip> [port]
Output: raw NV12 frames to stdout (width/height/stride from first frame header).
"""
import struct
import sys

MAGIC = 0x4F4D5430  # "OMT0" big-endian


def main():
    if len(sys.argv) < 2:
        print("Usage: pc_receiver.py <phone_ip> [port=6400]", file=sys.stderr)
        sys.exit(1)
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 6400

    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10.0)
    try:
        s.connect((host, port))
    except Exception as e:
        print(f"Connect failed: {e}", file=sys.stderr)
        sys.exit(2)
    s.settimeout(None)

    buf = b""
    need = 4 + 4 + 4 + 4 + 4 + 4 + 8 + 4  # magic, w, h, strideY, strideU, strideV, ts, payload_len
    width = height = stride_y = stride_u = stride_v = None
    frame_count = 0

    try:
        while True:
            if len(buf) < need:
                buf += s.recv(65536)
                if not buf:
                    break
                continue
            magic, width, height, stride_y, stride_u, stride_v, ts, payload_len = struct.unpack(
                ">IIIIIIQI", buf[:need]
            )
            buf = buf[need:]
            if magic != MAGIC:
                print(f"Bad magic 0x{magic:08X}", file=sys.stderr)
                sys.exit(3)
            if frame_count == 0:
                print(f"Stream: {width}x{height} strides Y={stride_y} U={stride_u} V={stride_v}", file=sys.stderr)
            need = payload_len
            while len(buf) < payload_len:
                buf += s.recv(65536)
                if not buf:
                    break
            if len(buf) < payload_len:
                break
            frame = buf[:payload_len]
            buf = buf[payload_len:]
            need = 4 + 4 + 4 + 4 + 4 + 4 + 8 + 4
            sys.stdout.buffer.write(frame)
            frame_count += 1
            if frame_count % 100 == 0:
                print(f"Frames: {frame_count}", file=sys.stderr)
    except BrokenPipeError:
        pass
    except KeyboardInterrupt:
        pass
    print(f"Done. Frames received: {frame_count}", file=sys.stderr)


if __name__ == "__main__":
    main()
