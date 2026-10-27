#!/usr/bin/env python3
"""
Mozc batch evaluator.
Communicates with mozc_server via Unix domain socket (IPC) using
hand-crafted protobuf encoding -- no .proto compilation required.

Reads kana-kanji pairs from a TSV file (kana<TAB>expected_kanji),
converts kana using Mozc, and computes CER.

Mozc IPC protocol (Linux / unix_ipc.cc):
  - One fresh connection per Command (server closes after each exchange)
  - Client sends: raw protobuf bytes (Command), NO size prefix
  - Client half-closes: shutdown(SHUT_WR) to signal end of request
  - Server reads until EOF, processes, writes raw protobuf response
  - Server closes the connection

  Note: The size-prefixed [uint32][protobuf] framing and IPCPathInfo
  handshake described in some references apply to Windows only.
  On Linux (unix_ipc.cc), the protocol is frameless and stateless
  -- one new connection per Command message.

Kana->Kanji conversion flow (verified from session.cc):
  1. CREATE_SESSION
  2. SEND_KEY for each hiragana character (key_code = Unicode codepoint)
  3. SEND_KEY with special_key=SPACE to trigger conversion
  4. Read Output.preedit segments for the conversion result
  5. SEND_COMMAND SUBMIT to commit (optional for eval)
  6. DELETE_SESSION

  Note: CONVERT_REVERSE does *not* do kana->kanji; it does kanji->kana
  (reverse lookup). Do not use it for forward conversion.
"""

import socket
import struct
import sys
import argparse


# ---------------------------------------------------------------------------
# Minimal protobuf encoder/decoder
# ---------------------------------------------------------------------------

def _varint_encode(v: int) -> bytes:
    out = []
    while True:
        bits = v & 0x7F
        v >>= 7
        if v:
            out.append(bits | 0x80)
        else:
            out.append(bits)
            break
    return bytes(out)


def _varint_decode(data: bytes, pos: int):
    result, shift = 0, 0
    while True:
        b = data[pos]; pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not (b & 0x80):
            break
    return result, pos


def _pb_field(field_number: int, wire_type: int, payload: bytes) -> bytes:
    tag = _varint_encode((field_number << 3) | wire_type)
    return tag + payload


def pb_varint(field_number: int, value: int) -> bytes:
    return _pb_field(field_number, 0, _varint_encode(value))


def pb_bytes(field_number: int, data: bytes) -> bytes:
    return _pb_field(field_number, 2, _varint_encode(len(data)) + data)


def pb_string(field_number: int, s: str) -> bytes:
    return pb_bytes(field_number, s.encode("utf-8"))


def pb_submsg(field_number: int, msg: bytes) -> bytes:
    return pb_bytes(field_number, msg)


def _decode_fields(data: bytes):
    """Decode protobuf to {field_number: [raw_bytes | int]}.

    Handles wire types 0 (varint), 2 (length-delimited), and 3/4 (groups).
    Proto2 groups (wire types SGROUP=3/EGROUP=4) are returned as raw bytes,
    same as embedded messages, so callers can recurse into them uniformly.
    """
    fields: dict = {}
    pos = 0
    while pos < len(data):
        tag, pos = _varint_decode(data, pos)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if wire_type == 0:  # varint
            val, pos = _varint_decode(data, pos)
            fields.setdefault(field_number, []).append(val)
        elif wire_type == 2:  # length-delimited
            length, pos = _varint_decode(data, pos)
            val = data[pos:pos + length]; pos += length
            fields.setdefault(field_number, []).append(val)
        elif wire_type == 3:  # SGROUP (proto2 group start)
            # Collect bytes until the matching EGROUP tag
            start = pos
            depth = 1
            while depth > 0 and pos < len(data):
                inner_tag, pos = _varint_decode(data, pos)
                inner_wire = inner_tag & 0x07
                inner_fn = inner_tag >> 3
                if inner_wire == 3:
                    depth += 1
                elif inner_wire == 4:
                    depth -= 1
                    if depth == 0:
                        break
                elif inner_wire == 0:
                    _, pos = _varint_decode(data, pos)
                elif inner_wire == 2:
                    length, pos = _varint_decode(data, pos)
                    pos += length
                elif inner_wire == 5:
                    pos += 4
                elif inner_wire == 1:
                    pos += 8
            # Store group content (excluding the EGROUP tag) as bytes
            end = pos - len(_varint_encode((field_number << 3) | 4))
            fields.setdefault(field_number, []).append(data[start:end])
        elif wire_type == 4:  # EGROUP -- should not appear at top level
            break
        elif wire_type == 5:  # 32-bit
            pos += 4
        elif wire_type == 1:  # 64-bit
            pos += 8
        else:
            raise ValueError(f"Unsupported wire type {wire_type} at pos {pos}")
    return fields


# ---------------------------------------------------------------------------
# Mozc IPC constants (from commands.proto and ipc.h)
# ---------------------------------------------------------------------------

# Input.CommandType
CREATE_SESSION = 1
DELETE_SESSION = 2
SEND_KEY       = 3
SEND_COMMAND   = 5   # NB: value is 5, not 4

# SessionCommand.CommandType
SUBMIT          = 2  # NB: value is 2, not 1
CONVERT_REVERSE = 8  # NB: value is 8, not 11

# KeyEvent.SpecialKey
SPACE = 4


# ---------------------------------------------------------------------------
# Mozc IPC client
# ---------------------------------------------------------------------------

def _find_socket(timeout: float = 10.0) -> str:
    """Find the mozc_server abstract socket path via /proc/net/unix."""
    import time
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with open("/proc/net/unix") as f:
                for line in f:
                    parts = line.split()
                    if len(parts) >= 8:
                        path = parts[7]
                        if path.startswith("@tmp/.mozc.") and path.endswith(".session"):
                            return "\0" + path[1:]
        except OSError:
            pass
        time.sleep(0.2)
    raise RuntimeError("mozc_server socket not found. Is mozc_server running?")


class MozcClient:
    """Client for mozc_server using the Linux unix_ipc.cc protocol.

    Protocol per unix_ipc.cc IPCServer::Loop / IPCClient::Call:
      - One fresh connection per Command (server accepts, processes, closes)
      - No handshake, no size framing
      - Client: connect -> sendall(raw_protobuf) -> shutdown(SHUT_WR)
      - Server: recv until EOF -> Process -> sendall(raw_protobuf) -> close
    """

    IPC_RESPONSESIZE = 16 * 8192  # 131072 bytes, matches ipc.h

    def __init__(self, socket_path: str):
        self._socket_path = socket_path

    def _call(self, command_bytes: bytes) -> bytes:
        """Send one Command and return the raw response protobuf."""
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.connect(self._socket_path)
        try:
            sock.sendall(command_bytes)
            # Half-close write side so server knows the request is complete
            sock.shutdown(socket.SHUT_WR)
            # Read response until server closes
            buf = b""
            while True:
                chunk = sock.recv(self.IPC_RESPONSESIZE)
                if not chunk:
                    break
                buf += chunk
            return buf
        finally:
            sock.close()

    def create_session(self) -> int:
        """Create a new Mozc session. Returns session_id.

        Protocol: send Input{type=CREATE_SESSION}, receive Output{id=session_id}
        """
        # Input { type: CREATE_SESSION }
        input_bytes = pb_varint(1, CREATE_SESSION)
        resp = self._call(input_bytes)
        output_fields = _decode_fields(resp)
        # Output.id is field 1 (uint64)
        session_id = output_fields.get(1, [0])[0]
        return session_id

    def delete_session(self, session_id: int):
        """Delete the Mozc session."""
        # Input { type: DELETE_SESSION, id: session_id }
        input_bytes = pb_varint(1, DELETE_SESSION) + pb_varint(2, session_id)
        self._call(input_bytes)

    def send_key(self, session_id: int, key_code: int = 0,
                 special_key: int = 0) -> dict:
        """Send a key event. Returns decoded Output fields.

        Use key_code for Unicode chars, special_key for special keys (SPACE=4).
        """
        # KeyEvent { key_code: N } or KeyEvent { special_key: N }
        key_event = b""
        if key_code:
            key_event += pb_varint(1, key_code)    # KeyEvent.key_code = field 1
        if special_key:
            key_event += pb_varint(3, special_key) # KeyEvent.special_key = field 3
        # Input { type: SEND_KEY, id: session_id, key: key_event }
        input_bytes = (pb_varint(1, SEND_KEY)
                       + pb_varint(2, session_id)    # Input.id = field 2
                       + pb_submsg(3, key_event))    # Input.key = field 3
        resp = self._call(input_bytes)
        return _decode_fields(resp)

    def convert_kana(self, session_id: int, kana: str) -> str:
        """Convert kana string to kanji.

        Flow:
          1. Send each hiragana character as SEND_KEY (key_code = Unicode)
          2. Send SPACE key to trigger conversion
          3. Extract first candidate from Output.preedit
        """
        # Type each kana character
        for ch in kana:
            self.send_key(session_id, key_code=ord(ch))

        # Send SPACE to trigger conversion
        output_fields = self.send_key(session_id, special_key=SPACE)

        # Try Output.result (field 4) -- committed text (unlikely at this stage)
        result_bytes = output_fields.get(4, [None])[0]
        if result_bytes:
            result_fields = _decode_fields(result_bytes)
            # Result.value is field 2
            value = result_fields.get(2, [None])[0]
            if value:
                return value.decode("utf-8")

        # Output.preedit (field 5) -- conversion candidates
        preedit_bytes = output_fields.get(5, [None])[0]
        if preedit_bytes:
            preedit_fields = _decode_fields(preedit_bytes)
            # Preedit.segment is field 2 (repeated group)
            segments = preedit_fields.get(2, [])
            text_parts = []
            for seg in segments:
                seg_fields = _decode_fields(seg)
                # Segment.value is field 4 (required string)
                value = seg_fields.get(4, [None])[0]
                if value:
                    text_parts.append(value.decode("utf-8"))
            if text_parts:
                return "".join(text_parts)

        return kana  # fallback: return input unchanged

    def reset_session(self, session_id: int):
        """Send REVERT to clear preedit state between conversions."""
        # Input { type: SEND_COMMAND, id: session_id,
        #         command: SessionCommand { type: REVERT=1 } }
        session_cmd = pb_varint(1, 1)  # SessionCommand.type = REVERT=1
        input_bytes = (pb_varint(1, SEND_COMMAND)
                       + pb_varint(2, session_id)
                       + pb_submsg(4, session_cmd))  # Input.command = field 4
        self._call(input_bytes)

    def close(self):
        pass  # No persistent connection


# ---------------------------------------------------------------------------
# CER computation
# ---------------------------------------------------------------------------

def cer(hypothesis: str, reference: str) -> float:
    h, r = list(hypothesis), list(reference)
    m, n = len(h), len(r)
    if n == 0:
        return 0.0 if m == 0 else 1.0
    dp = list(range(n + 1))
    for i in range(1, m + 1):
        prev = dp[:]
        dp[0] = i
        for j in range(1, n + 1):
            dp[j] = prev[j - 1] if h[i-1] == r[j-1] else 1 + min(prev[j], dp[j-1], prev[j-1])
    return dp[n] / n


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Evaluate Mozc on a TSV test set")
    parser.add_argument("tsv", help="Test TSV file: kana<TAB>expected_kanji")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--socket", default=None, help="Override mozc socket path")
    parser.add_argument("--output", default=None,
                        help="Write per-pair TSV to this file (kana TAB ref TAB hyp TAB cer)")
    args = parser.parse_args()

    socket_path = args.socket or _find_socket()
    print(f"Connecting to mozc_server: {repr(socket_path[:40])}", file=sys.stderr)

    client = MozcClient(socket_path)
    session_id = client.create_session()
    print(f"Session ID: {session_id}", file=sys.stderr)
    if session_id == 0:
        print("ERROR: Failed to create session (session_id=0)", file=sys.stderr)
        sys.exit(1)

    total_cer, exact, n = 0.0, 0, 0
    rows = []

    with open(args.tsv, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            parts = line.split("\t", 1)
            if len(parts) != 2:
                continue
            kana, expected = parts[0].strip(), parts[1].strip()
            if not kana or not expected:
                continue

            hypothesis = client.convert_kana(session_id, kana)
            # Reset preedit state for the next conversion
            client.reset_session(session_id)

            c = cer(hypothesis, expected)
            total_cer += c
            if hypothesis == expected:
                exact += 1
            rows.append((kana, expected, hypothesis, c))
            n += 1

            if args.limit and n >= args.limit:
                break

    client.delete_session(session_id)
    client.close()

    if n == 0:
        print("No samples found.", file=sys.stderr)
        sys.exit(1)

    print(f"Samples:     {n}")
    print(f"CER:         {total_cer / n * 100:.2f}%")
    print(f"Exact Match: {exact / n * 100:.2f}%")

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write("kana\tref\thyp\tcer\n")
            for kana, ref, hyp, c in rows:
                f.write(f"{kana}\t{ref}\t{hyp}\t{c:.6f}\n")
        print(f"Per-pair results saved: {args.output}")


if __name__ == "__main__":
    main()
