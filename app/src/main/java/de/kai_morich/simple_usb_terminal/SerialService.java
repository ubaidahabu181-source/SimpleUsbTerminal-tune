package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.IOException;

public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType { Connect, ConnectError, Read, IoError }

    private static class QueueItem {
        QueueType type;
        byte[] data;
        Exception e;
        QueueItem(QueueType type, byte[] data, Exception e) { this.type=type; this.data=data; this.e=e; }
    }

    private final IBinder binder;
    private SerialListener listener;
    private final java.util.concurrent.LinkedBlockingQueue<QueueItem> queue1, queue2;
    private SerialSocket socket;
    private boolean connected;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "serial_service";

    public SerialService() {
        binder = new SerialBinder();
        queue1 = new java.util.concurrent.LinkedBlockingQueue<>();
        queue2 = new java.util.concurrent.LinkedBlockingQueue<>();
    }

    @Override public void onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Serial Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    @Override public void onDestroy() { cancelNotification(); disconnect(); }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public void attach(SerialListener listener) {
        synchronized (this) { this.listener = listener; }
        for(java.util.concurrent.LinkedBlockingQueue<QueueItem> queue : new java.util.concurrent.LinkedBlockingQueue[]{queue1, queue2}) {
            while(!queue.isEmpty()) {
                QueueItem item = queue.remove();
                switch (item.type) {
                    case Connect: listener.onSerialConnect(); break;
                    case ConnectError: listener.onSerialConnectError(item.e); break;
                    case Read: listener.onSerialRead(item.data); break;
                    case IoError: listener.onSerialIoError(item.e); break;
                }
            }
        }
    }

    public void detach() { listener = null; }

    private void cancelNotification() { stopForeground(true); }

    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;

        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Serial USB Terminal")
                    .setContentText("Connected")
                    .setContentIntent(pi)
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Serial USB Terminal")
                    .setContentText("Connected")
                    .setContentIntent(pi)
                    .build();
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    public void disconnect() {
        connected = false;
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    public void write(byte[] data) throws IOException {
        if(!connected) throw new IOException("not connected");
        socket.write(data);
    }

    @Override public void onSerialConnect() {
        if(connected) {
            synchronized (this) {
                if(listener != null) { listener.onSerialConnect(); }
                else { queue1.add(new QueueItem(QueueType.Connect, null, null)); }
            }
        }
    }

    @Override public void onSerialConnectError(Exception e) {
        if(connected) {
            synchronized (this) {
                if(listener != null) { listener.onSerialConnectError(e); }
                else { queue2.add(new QueueItem(QueueType.ConnectError, null, e)); }
            }
            disconnect();
        }
    }

    @Override public void onSerialRead(byte[] data) {
        if(connected) {
            synchronized (this) {
                if(listener != null) { listener.onSerialRead(data); }
                else { queue1.add(new QueueItem(QueueType.Read, data, null)); }
            }
        }
    }

    @Override public void onSerialRead(java.util.ArrayDeque<byte[]> datas) {
        if(connected) {
            synchronized (this) {
                if(listener != null) { listener.onSerialRead(datas); }
                else { throw new UnsupportedOperationException(); }
            }
        }
    }

    @Override public void onSerialIoError(Exception e) {
        if(connected) {
            synchronized (this) {
                if(listener != null) { listener.onSerialIoError(e); }
                else { queue1.add(new QueueItem(QueueType.IoError, null, e)); }
            }
            disconnect();
        }
    }
}
