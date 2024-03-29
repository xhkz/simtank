package main.java;

import main.java.comm.*;
import main.java.model.Client;

import javax.swing.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class NetClient {
    private boolean isLeader = false;
    private String localIP = getInetAddress().getHostAddress();
    private DatagramSocket datagramSocket = null;
    private TankClient tankClient;
    private String serverIP;
    private int tcpPort = TankServer.TCP_PORT;
    private int udpPort;
    private Socket socket = null;
    private DataOutputStream dataOutputStream = null;
    private DataInputStream dataInputStream = null;
    private List<Client> clients = new ArrayList<>();
    private Client leader = null;
    private long minTimeStamp = Long.MAX_VALUE;

    public NetClient(TankClient tankClient) {
        this.tankClient = tankClient;
    }

    private static InetAddress getInetAddress() {
        try {
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            System.out.println("unknown host!");
        }
        return null;
    }

    public boolean isLeader() {
        return isLeader;
    }

    public void setServerIP(String serverIP) {
        this.serverIP = serverIP;
    }

    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }

    public void connect() {
        try {
            datagramSocket = new DatagramSocket(this.udpPort);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        new Thread(new UDPReceiveThread()).start();

        try {
            socket = new Socket(this.serverIP, this.tcpPort);
            dataOutputStream = new DataOutputStream(socket.getOutputStream());
            dataOutputStream.writeInt(this.udpPort);
            dataInputStream = new DataInputStream(socket.getInputStream());
            int id = dataInputStream.readInt();
            tankClient.tank.setId(id);

            System.out.println("Connected! ID: " + id);

            new Thread(new BeatThread()).start();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                //socket.close();
            }
        }

        while (leader == null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        TankNewMsg tankNewMsg = new TankNewMsg(this.tankClient);
        send(tankNewMsg);
    }

    public void send(Msg msg) {
        msg.send(this.datagramSocket, leader.getIp(), leader.getUdpPort());
    }

    private class BeatThread implements Runnable {
        public void run() {
            try {
                while (true) {
                    dataOutputStream.writeInt(1);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(dataInputStream));
                    String response = reader.readLine().trim();
                    System.out.println(response);

                    if (response.contains("Drop")) {
                        int tankID = Integer.parseInt(response.split(":")[1]);
                        tankClient.tanks.stream().filter(t -> t.getId() == tankID).forEach(t -> {
                            t.setLive(false);
                        });

                        int roll = JOptionPane.showConfirmDialog(null, "Roll a new leader", "Start", JOptionPane.DEFAULT_OPTION);
                        if (roll == 0) {
                            dataOutputStream.writeInt(new Random().nextInt(100) + 2);
                            continue;
                        }
                    }

                    synchronized (clients) {
                        clients.clear();
                        String[] IPArray = response.split("\\|");
                        for (String s : IPArray) {
                            String[] temp = s.split(":");
                            Client c = new Client(temp[0], Integer.parseInt(temp[1]));
                            clients.add(c);
                        }
                    }

                    if (leader == null || !leader.equals(clients.get(0)))
                        leader = clients.get(0);

                    isLeader = (leader.getIp().equals(localIP) && leader.getUdpPort() == udpPort);

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class UDPReceiveThread implements Runnable {

        byte[] buf = new byte[1024];
        DatagramPacket cache = new DatagramPacket(buf, buf.length);

        public void run() {
            while (datagramSocket != null) {
                DatagramPacket datagramPacket = new DatagramPacket(buf, buf.length);
                try {
                    datagramSocket.receive(datagramPacket);
                    cache = datagramPacket;

                    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(buf, 0, datagramPacket.getLength());
                    DataInputStream inputStream = new DataInputStream(byteArrayInputStream);
                    boolean forward = inputStream.readBoolean();

                    if (isLeader && forward) {
                        forwardMessage(datagramPacket);
                    }

                    parse(inputStream);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void forwardMessage(DatagramPacket datagramPacket) throws IOException {
            synchronized (clients) {
                // send to non-leaders
                for (Client c : clients.subList(1, clients.size())) {
                    datagramPacket.setSocketAddress(new InetSocketAddress(c.getIp(), c.getUdpPort()));
                    datagramSocket.send(datagramPacket);
                    System.out.println(String.format("Sending message to %s:%s", c.getIp(), c.getUdpPort()));
                }
            }
        }

        private void parse(DataInputStream inputStream) {
            try {
                int msgType = inputStream.readInt();
                Msg msg;
                switch (msgType) {
                    case Msg.TANK_NEW:
                        msg = new TankNewMsg(NetClient.this.tankClient);
                        msg.parse(inputStream);
                        break;
                    case Msg.TANK_MOVE:
                        msg = new TankMoveMsg(NetClient.this.tankClient);
                        msg.parse(inputStream);
                        break;
                    case Msg.MISSILE_NEW:
                        msg = new MissileNewMsg(NetClient.this.tankClient);
                        msg.parse(inputStream);
                        break;
                    case Msg.TANK_DEAD:
                        msg = new TankDeadMsg(NetClient.this.tankClient);
                        msg.parse(inputStream);
                        break;
                    case Msg.MISSILE_DEAD:
                        msg = new MissileDeadMsg(NetClient.this.tankClient);
                        msg.parse(inputStream);
                        break;
                    case Msg.ITEM_TAKEN:
                        msg = new ItemTakenMsg(NetClient.this.tankClient);
                        long timestamp = inputStream.readLong();
                        if (timestamp < minTimeStamp) {
                            if (isLeader) {
                                forwardMessage(cache);
                            }
                            minTimeStamp = timestamp;
                            msg.parse(inputStream);
                        }
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
