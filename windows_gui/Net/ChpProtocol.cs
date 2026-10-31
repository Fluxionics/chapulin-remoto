using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace ChapulinRemoto.Net
{
    public static class ChpProtocol
    {
        public const ushort Magic = 0x4843;
        public const byte Version = 1;
        public const int HeaderSize = 12;
        public const int DefaultPort = 47474;

        public const byte TypeHello = 0x01;
        public const byte TypeHelloAck = 0x02;
        public const byte TypeAuth = 0x03;
        public const byte TypeAuthResult = 0x04;
        public const byte TypeVideoFrame = 0x05;
        public const byte TypeInput = 0x06;
        public const byte TypePing = 0x07;
        public const byte TypePong = 0x08;
        public const byte TypeDisconnect = 0x09;
        public const byte TypeSetQuality = 0x0A;

        public static byte[] BuildPacket(byte type, uint seq, byte[]? payload = null)
        {
            int payloadLen = payload?.Length ?? 0;
            byte[] packet = new byte[HeaderSize + payloadLen];

            using var ms = new MemoryStream(packet);
            using var writer = new BinaryWriter(ms);

            writer.Write(Magic);
            writer.Write(Version);
            writer.Write(type);
            writer.Write((uint)payloadLen);
            writer.Write(seq);

            if (payload != null && payloadLen > 0)
            {
                writer.Write(payload);
            }

            return packet;
        }

        public static (byte type, uint seq, byte[] payload) ReadPacket(Stream stream)
        {
            byte[] header = new byte[HeaderSize];
            ReadExact(stream, header, 0, HeaderSize);

            using var ms = new MemoryStream(header);
            using var reader = new BinaryReader(ms);

            ushort magic = reader.ReadUInt16();
            if (magic != Magic) throw new InvalidDataException("Magic inválido");

            byte version = reader.ReadByte();
            if (version != Version) throw new InvalidDataException($"Versión no soportada: {version}");

            byte type = reader.ReadByte();
            uint payloadLen = reader.ReadUInt32();
            uint seq = reader.ReadUInt32();

            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0)
            {
                ReadExact(stream, payload, 0, (int)payloadLen);
            }

            return (type, seq, payload);
        }

        public static void ReadExact(Stream stream, byte[] buffer, int offset, int count)
        {
            int read = 0;
            while (read < count)
            {
                int r = stream.Read(buffer, offset + read, count - read);
                if (r <= 0) throw new EndOfStreamException();
                read += r;
            }
        }
    }
}
