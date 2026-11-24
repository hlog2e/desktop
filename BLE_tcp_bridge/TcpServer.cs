using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;

namespace BLE_tcp_driver
{
    class TcpServer
    {
        private TcpListener _listener;
        private readonly BleCore _bleCore;
        private readonly int _port;
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private readonly object _clientLock = new object();
        private volatile bool _running;

        private DeviceStatusInfo _deviceStatus;
        private readonly object _statusLock = new object();

        /// <summary>
        /// 日志事件 (从后台线程触发, UI层需BeginInvoke)
        /// </summary>
        public event Action<string> OnLog;

        /// <summary>
        /// TCP客户端数量变化事件
        /// </summary>
        public event Action<int> OnClientCountChanged;

        public int Port => _port;
        public int ClientCount { get { lock (_clientLock) return _clients.Count; } }

        public TcpServer(BleCore bleCore, int port = 9000)
        {
            _bleCore = bleCore;
            _port = port;
        }

        public void Start()
        {
            if (_running) return;
            _listener = new TcpListener(IPAddress.Any, _port);
            _listener.Start();
            _running = true;

            // 订阅BLE通知, 转发给所有TCP客户端
            _bleCore.ReceiveNotifyData += OnBleNotify;

            Log($"TCP 서버 시작: 포트 {_port}");
            Task.Run(() => AcceptLoop());
        }

        public void Stop()
        {
            if (!_running) return;
            _running = false;
            _bleCore.ReceiveNotifyData -= OnBleNotify;

            try { _listener?.Stop(); } catch { }

            lock (_clientLock)
            {
                foreach (var c in _clients)
                    try { c.Close(); } catch { }
                _clients.Clear();
            }
            OnClientCountChanged?.Invoke(0);
            Log("TCP 서버를 멈췄어요");
        }

        /// <summary>
        /// 接受TCP客户端连接循环
        /// </summary>
        private async Task AcceptLoop()
        {
            while (_running)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync();
                    lock (_clientLock) _clients.Add(client);

                    string ep = GetEndpointString(client);
                    Log($"TCP 클라이언트 연결됨: {ep}");
                    OnClientCountChanged?.Invoke(ClientCount);

                    var _ = Task.Run(() => ClientLoop(client));
                }
                catch (ObjectDisposedException) { break; }
                catch (SocketException) { if (!_running) break; }
                catch (Exception ex)
                {
                    if (_running) Log($"연결 받기 오류: {ex.Message}");
                }
            }
        }

        /// <summary>
        /// 单个TCP客户端读取循环
        /// </summary>
        private async Task ClientLoop(TcpClient client)
        {
            try
            {
                var stream = client.GetStream();
                byte[] header = new byte[3];

                while (_running && client.Connected)
                {
                    // 读包头: [Type:1][Length:2]
                    int n = await ReadExact(stream, header, 3);
                    if (n < 3) break;

                    PacketType type = (PacketType)header[0];
                    int dataLen = header[1] | (header[2] << 8);

                    // 读包体
                    byte[] data = null;
                    if (dataLen > 0)
                    {
                        if (dataLen > 65535) break; // 防止异常大包
                        data = new byte[dataLen];
                        n = await ReadExact(stream, data, dataLen);
                        if (n < dataLen) break;
                    }

                    HandlePacket(client, type, data);
                }
            }
            catch (IOException) { }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
            catch (Exception ex) { Log($"클라이언트 처리 오류: {ex.Message}"); }
            finally
            {
                RemoveClient(client);
            }
        }

        /// <summary>
        /// 处理收到的TCP包, 路由到BLE或返回查询结果
        /// </summary>
        private void HandlePacket(TcpClient client, PacketType type, byte[] data)
        {
            switch (type)
            {
                case PacketType.WriteData:
                    if (_bleCore.CurrentDataCharacteristic != null)
                    {
                        for (int i = 0; i < data.Count(); i += 200)
                        {
                            _bleCore.WriteDataToCharacterstuc(_bleCore.CurrentDataCharacteristic, data.Skip(i).Take(Math.Min(data.Count() - i, 200)).ToArray());
                        }
                        //_bleCore.WriteDataToCharacterstuc(_bleCore.CurrentDataCharacteristic, data ?? new byte[0]);
                        Log($"→BLE 데이터(0x7341) [{data?.Length ?? 0}바이트]");
                    }
                    else
                        Log("BLE 데이터 특성(0x7341)이 준비되지 않았어요");
                    break;

                case PacketType.WriteCommand:
                    // 记录状态信息
                    if (ProtocolHelper.IsClaudeStatusUpload(data))
                    {
                        ProtocolHelper.LastClaudeState = data;
                    }
                    if (_bleCore.CurrentWriteCharacteristic != null)
                    {
                        for (int i = 0; i < data.Count(); i += 20)
                        {
                            _bleCore.WriteDataToCharacterstuc(_bleCore.CurrentWriteCharacteristic, data.Skip(i).Take(Math.Min(data.Count() - i, 20)).ToArray());
                        }
                        //_bleCore.WriteDataToCharacterstuc(_bleCore.CurrentWriteCharacteristic, data ?? new byte[0]);
                        Log($"→BLE 명령(0x7343) [{data?.Length ?? 0}바이트]");
                    }
                    else
                        Log("BLE 명령 특성(0x7343)이 준비되지 않았어요");
                    break;

                case PacketType.QueryBleStatus:
                    var status = BuildBleStatus();
                    SendToClient(client, ProtocolHelper.BuildBleStatusPacket(status));
                    Log("BLE 상태 조회에 응답했어요");
                    break;

                case PacketType.QueryDeviceInfo:
                    DeviceStatusInfo info;
                    lock (_statusLock) info = _deviceStatus;
                    SendToClient(client, ProtocolHelper.BuildDeviceInfoPacket(info));
                    Log("기기 정보 조회에 응답했어요");
                    break;

                default:
                    Log($"알 수 없는 패킷 종류: 0x{(byte)type:X2}");
                    break;
            }
        }

        /// <summary>
        /// 从BleCore获取当前BLE连接状态
        /// </summary>
        private BleStatusInfo BuildBleStatus()
        {
            var dev = _bleCore.CurrentDevice;
            bool connected = dev != null &&
                dev.ConnectionStatus == BluetoothConnectionStatus.Connected;

            string name = "";
            string mac = "";
            if (dev != null)
            {
                name = dev.Name ?? "";
                byte[] macBytes = BitConverter.GetBytes(dev.BluetoothAddress);
                Array.Reverse(macBytes);
                mac = BitConverter.ToString(macBytes, 2, 6).Replace('-', ':');
            }

            bool isTarget = _bleCore.CurrentDataCharacteristic != null
                         && _bleCore.CurrentWriteCharacteristic != null
                         && _bleCore.CurrentNotifyCharacteristic != null;

            return new BleStatusInfo
            {
                Connected = connected,
                DeviceName = name,
                MacAddress = mac,
                IsTargetDevice = isTarget
            };
        }

        /// <summary>
        /// BLE通知回调 → 过滤设备状态通知, 其余广播给所有TCP客户端
        /// </summary>
        private void OnBleNotify(GattCharacteristic sender, byte[] data)
        {
            // 设备状态通知: 解析并更新内存, 不广播给客户端
            if (ProtocolHelper.IsDeviceStatusNotification(data))
            {
                var newStatus = ProtocolHelper.ParseDeviceStatusFromNotification(data);
                lock (_statusLock) _deviceStatus = newStatus;
                Log($"키보드 상태 업데이트: 배터리={newStatus.BatteryLevel} 신호={newStatus.SignalStrength} " +
                    $"펌웨어={newStatus.FirmwareVersionMain}.{newStatus.FirmwareVersionSub} " +
                    $"키보드 모드={newStatus.WorkMode} 조명={newStatus.LightMode} 스위치={newStatus.SwitchState}");
                return;
            }

            byte[] packet = ProtocolHelper.BuildPacket(PacketType.BleNotify, data);
            BroadcastToAll(packet);
        }

        /// <summary>
        /// 向所有已连接的TCP客户端广播数据
        /// </summary>
        public void BroadcastToAll(byte[] packet)
        {
            List<TcpClient> snapshot;
            lock (_clientLock) snapshot = new List<TcpClient>(_clients);
            foreach (var c in snapshot)
                SendToClient(c, packet);
        }

        private void SendToClient(TcpClient client, byte[] packet)
        {
            try
            {
                if (client.Connected)
                {
                    var stream = client.GetStream();
                    stream.Write(packet, 0, packet.Length);
                }
            }
            catch { RemoveClient(client); }
        }

        private void RemoveClient(TcpClient client)
        {
            bool removed;
            lock (_clientLock) removed = _clients.Remove(client);
            if (removed)
            {
                string ep = GetEndpointString(client);
                Log($"TCP 클라이언트 연결이 끊겼어요: {ep}");
                try { client.Close(); } catch { }
                OnClientCountChanged?.Invoke(ClientCount);
            }
        }

        /// <summary>
        /// 从NetworkStream精确读取count字节
        /// </summary>
        private static async Task<int> ReadExact(NetworkStream stream, byte[] buf, int count)
        {
            int total = 0;
            while (total < count)
            {
                int n = await stream.ReadAsync(buf, total, count - total);
                if (n == 0) return total; // 连接关闭
                total += n;
            }
            return total;
        }

        private static string GetEndpointString(TcpClient client)
        {
            try { return client.Client.RemoteEndPoint?.ToString() ?? "unknown"; }
            catch { return "unknown"; }
        }

        private void Log(string msg) => OnLog?.Invoke(msg);

        /// <summary>
        /// 获取本机局域网IP地址
        /// </summary>
        public static string GetLocalIPAddress()
        {
            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                var ip = host.AddressList.FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork);
                return ip?.ToString() ?? "127.0.0.1";
            }
            catch { return "127.0.0.1"; }
        }
    }
}
