using System;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace ChapulinRemoto.Net
{
    public class ChpClient
    {
        private TcpClient? _client;
        private NetworkStream? _stream;
        private CancellationTokenSource? _cts;
        private readonly object _sendLock = new();
        private uint _seq = 0;

        public bool IsConnected => _client != null && _client.Connected;

        public event Action<byte[], ushort, ushort>? OnFrameReceived;
        public event Action<string>? OnDisconnected;

        public async Task ConnectAsync(string ip, int port, string pin)
        {
            Disconnect();

            _client = new TcpClient
            {
                NoDelay = true,
                // El celular mantiene viva la conexion con Ping cada 1s; 30s de
                // tolerancia por si la captura tarda en arrancar.
                ReceiveTimeout = 30000,
                SendTimeout = 6000
            };

            await _client.ConnectAsync(ip, port);
            _stream = _client.GetStream();

            byte[] helloPayload = new byte[16];
            using (var ms = new MemoryStream(helloPayload))
            using (var bw = new BinaryWriter(ms))
            {
                bw.Write((ushort)1);
                bw.Write((ushort)1);
                bw.Write((byte)1);
                bw.Write((byte)1);
                bw.Write((byte)0);
                bw.Write((byte)0);
                bw.Write(1080);
                bw.Write(2400);
            }

            SendPacket(ChpProtocol.TypeHello, helloPayload);

            var (ackType, _, _) = ChpProtocol.ReadPacket(_stream);
            if (ackType != ChpProtocol.TypeHelloAck)
            {
                throw new InvalidOperationException("Respuesta inválida del celular.");
            }

            byte[] pinBytes = Encoding.UTF8.GetBytes(pin.Trim());
            SendPacket(ChpProtocol.TypeAuth, pinBytes);

            var (authType, _, authPayload) = ChpProtocol.ReadPacket(_stream);
            if (authType != ChpProtocol.TypeAuthResult)
            {
                throw new InvalidOperationException("Respuesta inesperada al PIN.");
            }

            byte res = authPayload.Length > 0 ? authPayload[0] : (byte)1;
            if (res == 1)
            {
                throw new UnauthorizedAccessException("PIN incorrecto. Revisa el PIN en la pantalla de tu celular.");
            }
            if (res == 2)
            {
                throw new UnauthorizedAccessException("Acceso bloqueado por el celular.");
            }
            if (res != 0)
            {
                throw new InvalidOperationException($"Error de autenticación (código {res}).");
            }

            _cts = new CancellationTokenSource();
            _ = Task.Run(() => ReadLoop(_cts.Token));
        }

        public void Disconnect()
        {
            _cts?.Cancel();
            _cts = null;

            if (_stream != null)
            {
                try { _stream.Close(); } catch { }
                _stream = null;
            }

            if (_client != null)
            {
                try { _client.Close(); } catch { }
                _client = null;
            }
        }

        private void SendPacket(byte type, byte[] payload)
        {
            lock (_sendLock)
            {
                if (_stream == null) return;
                byte[] packet = ChpProtocol.BuildPacket(type, ++_seq, payload);
                _stream.Write(packet, 0, packet.Length);
                _stream.Flush();
            }
        }

        public void SendInput(byte kind, byte button, ushort x, ushort y, short delta = 0, ushort key = 0)
        {
            if (!IsConnected) return;

            byte[] payload = new byte[14];
            using (var ms = new MemoryStream(payload))
            using (var bw = new BinaryWriter(ms))
            {
                bw.Write(kind);
                bw.Write(button);
                bw.Write(x);
                bw.Write(y);
                bw.Write(delta);
                bw.Write(key);
                bw.Write((uint)0);
            }

            try
            {
                SendPacket(ChpProtocol.TypeInput, payload);
            }
            catch
            {
                Disconnect();
            }
        }

        public void SendKey(ushort keyCode)
        {
            SendInput(4, 0, 0, 0, 0, keyCode);
            Thread.Sleep(30);
            SendInput(5, 0, 0, 0, 0, keyCode);
        }

        private void ReadLoop(CancellationToken token)
        {
            try
            {
                while (!token.IsCancellationRequested && _stream != null)
                {
                    var (type, _, payload) = ChpProtocol.ReadPacket(_stream);

                    if (type == ChpProtocol.TypeVideoFrame)
                    {
                        if (payload.Length >= 12)
                        {
                            using var ms = new MemoryStream(payload);
                            using var br = new BinaryReader(ms);

                            ushort w = br.ReadUInt16();
                            ushort h = br.ReadUInt16();
                            br.ReadByte();
                            br.ReadByte();
                            br.ReadUInt16();
                            br.ReadUInt32();

                            int imgLen = payload.Length - 12;
                            if (imgLen > 0)
                            {
                                byte[] jpeg = new byte[imgLen];
                                Buffer.BlockCopy(payload, 12, jpeg, 0, imgLen);
                                OnFrameReceived?.Invoke(jpeg, w, h);
                            }
                        }
                    }
                    else if (type == ChpProtocol.TypePing)
                    {
                        SendPacket(ChpProtocol.TypePong, payload);
                    }
                    else if (type == ChpProtocol.TypeDisconnect)
                    {
                        break;
                    }
                }
            }
            catch (Exception ex)
            {
                if (!token.IsCancellationRequested)
                {
                    OnDisconnected?.Invoke(ex.Message);
                }
            }
            finally
            {
                Disconnect();
            }
        }
    }
}
