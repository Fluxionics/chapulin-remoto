using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace ChapulinRemoto.Net
{
    public class ChpHostServer
    {
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public MOUSEKEYBDHARDWAREINPUT mkhi;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct MOUSEKEYBDHARDWAREINPUT
        {
            [FieldOffset(0)]
            public MOUSEINPUT mi;
            [FieldOffset(0)]
            public KEYBDINPUT ki;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        private const uint INPUT_MOUSE = 0;
        private const uint INPUT_KEYBOARD = 1;
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
        private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_SCANCODE = 0x0008;
        private const uint KEYEVENTF_UNICODE = 0x0004;

        private TcpListener? _listener;
        private CancellationTokenSource? _cts;
        private ImageCodecInfo? _jpegEncoder;
        private EncoderParameters? _encoderParams;

        public int Port { get; private set; } = 47474;
        public string Pin { get; private set; } = "123456";
        public bool IsRunning { get; private set; }
        public bool IsClientConnected { get; private set; }
        public string? ConnectedClientIp { get; private set; }

        public event Action? OnStatusChanged;

        public void Start(int port = 47474)
        {
            Stop();
            Port = port;
            Pin = new Random().Next(100000, 999999).ToString();

            _jpegEncoder = ImageCodecInfo.GetImageEncoders().FirstOrDefault(c => c.FormatID == ImageFormat.Jpeg.Guid);
            _encoderParams = new EncoderParameters(1);
            _encoderParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 65L);

            _listener = new TcpListener(IPAddress.Any, Port);
            _listener.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _listener.Start();
            IsRunning = true;

            _cts = new CancellationTokenSource();
            _ = Task.Run(() => AcceptLoop(_cts.Token));
            OnStatusChanged?.Invoke();
        }

        public void Stop()
        {
            IsRunning = false;
            _cts?.Cancel();
            _cts = null;

            try
            {
                _listener?.Stop();
            }
            catch { }
            _listener = null;

            IsClientConnected = false;
            ConnectedClientIp = null;
            OnStatusChanged?.Invoke();
        }

        private async Task AcceptLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested && _listener != null)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync(token);
                    client.NoDelay = true;
                    _ = Task.Run(() => HandleClient(client, token));
                }
                catch
                {
                    break;
                }
            }
        }

        private void HandleClient(TcpClient client, CancellationToken token)
        {
            using var stream = client.GetStream();
            try
            {
                var (helloType, _, _) = ChpProtocol.ReadPacket(stream);
                if (helloType != ChpProtocol.TypeHello) return;

                var screen = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1920, 1080);
                byte[] ackPayload = new byte[24 + 2];
                using (var ms = new MemoryStream(ackPayload))
                using (var bw = new BinaryWriter(ms))
                {
                    bw.Write((ushort)1);
                    bw.Write((ushort)Port);
                    bw.Write(screen.Width);
                    bw.Write(screen.Height);
                    bw.Write((byte)1);
                    bw.Write((byte)1);
                    bw.Write((byte)2);
                    bw.Write(new byte[9]);
                    bw.Write(Encoding.UTF8.GetBytes("PC"));
                }
                byte[] ackPacket = ChpProtocol.BuildPacket(ChpProtocol.TypeHelloAck, 0, ackPayload);
                stream.Write(ackPacket, 0, ackPacket.Length);

                var (authType, _, authPayload) = ChpProtocol.ReadPacket(stream);
                if (authType != ChpProtocol.TypeAuth) return;

                string receivedPin = Encoding.UTF8.GetString(authPayload).Trim();
                if (receivedPin != Pin)
                {
                    byte[] failPacket = ChpProtocol.BuildPacket(ChpProtocol.TypeAuthResult, 0, new byte[] { 1 });
                    stream.Write(failPacket, 0, failPacket.Length);
                    return;
                }

                byte[] okPacket = ChpProtocol.BuildPacket(ChpProtocol.TypeAuthResult, 0, new byte[] { 0 });
                stream.Write(okPacket, 0, okPacket.Length);

                IsClientConnected = true;
                ConnectedClientIp = (client.Client.RemoteEndPoint as IPEndPoint)?.Address.ToString();
                OnStatusChanged?.Invoke();

                using var clientCts = CancellationTokenSource.CreateLinkedTokenSource(token);
                var captureTask = Task.Run(() => CaptureLoop(stream, clientCts.Token));
                var inputTask = Task.Run(() => ReadInputLoop(stream, clientCts.Token));

                Task.WaitAny(captureTask, inputTask);
                clientCts.Cancel();
            }
            catch
            {
            }
            finally
            {
                client.Close();
                IsClientConnected = false;
                ConnectedClientIp = null;
                OnStatusChanged?.Invoke();
            }
        }

        private void CaptureLoop(NetworkStream stream, CancellationToken token)
        {
            uint seq = 0;
            var screen = Screen.PrimaryScreen;
            if (screen == null || _jpegEncoder == null || _encoderParams == null)
            {
                return; // sin pantalla o sin codificador no hay nada que capturar
            }
            var bounds = screen.Bounds;
            int w = bounds.Width;
            int h = bounds.Height;
            if (w <= 0 || h <= 0) return;

            int captureW = w > 1920 ? 1920 : w;
            int captureH = (int)((long)captureW * h / w);
            if (captureH <= 0) captureH = 1;

            using var bmp = new Bitmap(w, h, PixelFormat.Format32bppRgb);
            using var g = Graphics.FromImage(bmp);

            while (!token.IsCancellationRequested)
            {
                var start = DateTime.UtcNow;

                byte[] jpegBytes;
                try
                {
                    g.CopyFromScreen(0, 0, 0, 0, new Size(w, h), CopyPixelOperation.SourceCopy);

                    if (captureW != w || captureH != h)
                    {
                        using var scaled = new Bitmap(bmp, captureW, captureH);
                        using var ms = new MemoryStream();
                        scaled.Save(ms, _jpegEncoder!, _encoderParams);
                        jpegBytes = ms.ToArray();
                    }
                    else
                    {
                        using var ms = new MemoryStream();
                        bmp.Save(ms, _jpegEncoder!, _encoderParams);
                        jpegBytes = ms.ToArray();
                    }
                }
                catch
                {
                    // Un frame puntual puede fallar (pantalla bloqueada, cambio de
                    // resolucion); se salta y se reintenta en el siguiente ciclo.
                    Thread.Sleep(66);
                    continue;
                }

                byte[] framePayload = new byte[12 + jpegBytes.Length];
                using (var ms = new MemoryStream(framePayload))
                using (var bw = new BinaryWriter(ms))
                {
                    bw.Write((ushort)captureW);
                    bw.Write((ushort)captureH);
                    bw.Write((byte)1);
                    bw.Write((byte)0);
                    bw.Write((ushort)100);
                    bw.Write((uint)DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                    bw.Write(jpegBytes);
                }

                byte[] packet = ChpProtocol.BuildPacket(ChpProtocol.TypeVideoFrame, ++seq, framePayload);
                try
                {
                    lock (stream)
                    {
                        stream.Write(packet, 0, packet.Length);
                        stream.Flush();
                    }
                }
                catch
                {
                    return; // cliente desconectado; se corta el bucle de captura
                }

                int elapsed = (int)(DateTime.UtcNow - start).TotalMilliseconds;
                int delay = 33 - elapsed;
                if (delay > 0) Thread.Sleep(delay);
            }
        }

        private void ReadInputLoop(NetworkStream stream, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                var (type, _, payload) = ChpProtocol.ReadPacket(stream);
                if (type == ChpProtocol.TypeInput && payload.Length >= 14)
                {
                    ProcessInput(payload);
                }
                else if (type == ChpProtocol.TypePing)
                {
                    byte[] pong = ChpProtocol.BuildPacket(ChpProtocol.TypePong, 0, payload);
                    lock (stream)
                    {
                        stream.Write(pong, 0, pong.Length);
                        stream.Flush();
                    }
                }
                else if (type == ChpProtocol.TypeDisconnect)
                {
                    break;
                }
            }
        }

        // x,y llegan YA normalizados 0..65535 sobre el escritorio virtual
        // (igual que phone_client.cpp), asi que SendInput con ABSOLUTE|VIRTUALDESK
        // los usa directo: NO hay que re-escalarlos a resolucion de pantalla.
        private void ProcessInput(byte[] payload)
        {
            using var ms = new MemoryStream(payload);
            using var br = new BinaryReader(ms);

            byte kind = br.ReadByte();
            byte button = br.ReadByte();
            ushort x = br.ReadUInt16();
            ushort y = br.ReadUInt16();
            short delta = br.ReadInt16();
            ushort key = br.ReadUInt16();
            // flags es un int de 4 bytes al final (Chp.inputPayload); puede faltar
            // en paquetes viejos.
            uint flags = ms.Length - ms.Position >= 4 ? br.ReadUInt32() : 0u;
            bool extended = (flags & 0x01) != 0; // Chp.FLAG_EXTENDED

            switch (kind)
            {
                // MOVE
                case 0:
                    SendMouse((short)x, (short)y, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0);
                    break;

                // BUTTON_DOWN
                case 1:
                    uint downFlag = button switch
                    {
                        1 => MOUSEEVENTF_RIGHTDOWN,
                        2 => MOUSEEVENTF_MIDDLEDOWN,
                        _ => MOUSEEVENTF_LEFTDOWN
                    };
                    SendMouse((short)x, (short)y, downFlag | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0);
                    break;

                // BUTTON_UP
                case 2:
                    uint upFlag = button switch
                    {
                        1 => MOUSEEVENTF_RIGHTUP,
                        2 => MOUSEEVENTF_MIDDLEUP,
                        _ => MOUSEEVENTF_LEFTUP
                    };
                    SendMouse((short)x, (short)y, upFlag | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK, 0);
                    break;

                // WHEEL
                case 3:
                    SendMouse(0, 0, MOUSEEVENTF_WHEEL, (uint)(int)delta);
                    break;

                // KEY_DOWN (scancode) -> igual que injector.cpp: KEYEVENTF_SCANCODE + extended
                case 4:
                    SendKey(key, extended, down: true);
                    break;

                // KEY_UP (scancode)
                case 5:
                    SendKey(key, extended, down: false);
                    break;

                // CHAR (unicode): up+down con KEYEVENTF_UNICODE para tildes/ñ
                case 6:
                    SendChar((char)key);
                    break;
            }
        }

        private void SendMouse(int dx, int dy, uint dwFlags, uint mouseData)
        {
            INPUT input = new INPUT();
            input.type = INPUT_MOUSE;
            input.mkhi.mi.dx = dx;
            input.mkhi.mi.dy = dy;
            input.mkhi.mi.dwFlags = dwFlags;
            input.mkhi.mi.mouseData = mouseData;
            SendInput(1, new[] { input }, Marshal.SizeOf(typeof(INPUT)));
        }

        private void SendKey(ushort scanCode, bool extended, bool down)
        {
            uint dwFlags = KEYEVENTF_SCANCODE | (down ? 0u : KEYEVENTF_KEYUP);
            if (extended) dwFlags |= KEYEVENTF_EXTENDEDKEY;

            INPUT input = new INPUT();
            input.type = INPUT_KEYBOARD;
            input.mkhi.ki.wScan = scanCode;
            input.mkhi.ki.dwFlags = dwFlags;
            SendInput(1, new[] { input }, Marshal.SizeOf(typeof(INPUT)));
        }

        private void SendChar(char c)
        {
            INPUT down = new INPUT();
            down.type = INPUT_KEYBOARD;
            down.mkhi.ki.wScan = c;
            down.mkhi.ki.dwFlags = KEYEVENTF_UNICODE;

            INPUT up = new INPUT();
            up.type = INPUT_KEYBOARD;
            up.mkhi.ki.wScan = c;
            up.mkhi.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;

            SendInput(2, new[] { down, up }, Marshal.SizeOf(typeof(INPUT)));
        }
    }
}
