package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }
    private Connected connected = Connected.False;
    private static final String ACTION_USB_PERMISSION = "de.kai_morich.simple_usb_terminal.USB_PERMISSION";

    private String deviceAddress;
    private int deviceId;
    private int portNum;
    private int baudRate = 115200;

    private SerialService service;
    private boolean initialStart = true;
    private PendingIntent permissionIntent;
    private boolean receiverRegistered = false;
    private UsbSerialPort currentPort;
    private UsbDeviceConnection currentConnection;
    private Thread readThread;
    private boolean stopRead = false;

    private int[] RPM_GRID = {500,700,900,1100,1300,1500,1800,2200,2600,3000,3500,4000,4500,5000,5750,6500};
    private int[] MAP_GRID = {20,25,30,35,40,45,50,55,60,65,70,75,80,85,92,100};
    private StringBuilder logBuffer = new StringBuilder();
    private int logCount = 0;
    // BLACKBOX BUFFER - 5 detik history @250ms = 20 sample
    private static class Sample { long t; int rpm,map,adv; Sample(long t,int r,int m,int a){this.t=t;rpm=r;map=m;adv=a;} }
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final ArrayDeque<Sample> pendingPost = new ArrayDeque<>();
    private boolean waitingPost = false;
    private long logTriggerTime = 0;
    private String pendingNote = "";
    private int pendingRow=0, pendingCol=0;
    
    private TextView receiveText, rpmValue, mapValue, advValue, statusText, limiterText, dfcoText, liveCellInfo, logText;
    private ProgressBar rpmBar;
    private EditText sendText;
    private LinearLayout mapTableContainer;
    private Button btnGet, btnSave, btnLoad, sendBtn, btnLog, btnShareLog, btnClearLog;
    
    private int[][] advMap = new int[16][16];
    private int currentRpm = 0, currentMap = 45, currentAdv = 16;
    private String buffer = "";

    private ActivityResultLauncher<Intent> loadFileLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private String pendingSaveContent = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        Bundle args = getArguments();
        if(args != null) {
            deviceId = args.getInt("device", -1);
            portNum = args.getInt("port", 0);
            baudRate = args.getInt("baud", 115200);
            if(args.containsKey("device") && args.get("device") instanceof String) deviceAddress = args.getString("device");
            else deviceAddress = String.valueOf(deviceId);
        }
        loadFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if(uri != null) loadMapFromUri(uri);
            }
        });
        saveFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if(uri != null) saveMapToUri(uri, pendingSaveContent);
            }
        });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        rpmValue = view.findViewById(R.id.rpmValue);
        mapValue = view.findViewById(R.id.mapValue);
        advValue = view.findViewById(R.id.advValue);
        statusText = view.findViewById(R.id.statusText);
        limiterText = view.findViewById(R.id.limiterText);
        dfcoText = view.findViewById(R.id.dfcoText);
        liveCellInfo = view.findViewById(R.id.liveCellInfo);
        rpmBar = view.findViewById(R.id.rpmBar);
        sendText = view.findViewById(R.id.send_text);
        sendBtn = view.findViewById(R.id.send_btn);
        btnGet = view.findViewById(R.id.btnGet);
        btnSave = view.findViewById(R.id.btnSave);
        btnLoad = view.findViewById(R.id.btnLoad);
        mapTableContainer = view.findViewById(R.id.mapTableContainer);
        logText = view.findViewById(R.id.logText);
        btnLog = view.findViewById(R.id.btnLog);
        btnShareLog = view.findViewById(R.id.btnShareLog);
        btnClearLog = view.findViewById(R.id.btnClearLog);
        if(logText==null){
          // create programmatically if layout old
          logText = new TextView(getContext());
          mapTableContainer.addView(logText);
        }
        if(logBuffer.length()==0){
          logBuffer.append("// TARUNA LOG - Aman di RPM tinggi, tidak kirim ke ECU\n");
          logBuffer.append("// Format: WAKTU - RPM, MAP, ADV - ROW,COL - CATATAN\n\n");
        }

        for(int r=0;r<16;r++) for(int c=0;c<16;c++) advMap[r][c]=5;
        buildMapTable();
        status("MAP INIT 5 - Matikan mesin (RPM 0) lalu klik GET");

        btnGet.setOnClickListener(v -> {
            send("$GET");
        });
        
        if(btnLog!=null){
            btnLog.setOnClickListener(v -> doLog());
        }
        if(btnShareLog!=null){
            btnShareLog.setOnClickListener(v -> shareLog());
        }
        if(btnClearLog!=null){
            btnClearLog.setOnClickListener(v -> clearLog());
        }
        // Long press LOG for quick log without dialog
        if(btnLog!=null){
            btnLog.setOnLongClickListener(v -> { quickLog(); return true; });
        }
        btnLoad.setOnClickListener(v -> openFilePicker());
        btnSave.setOnClickListener(v -> { 
            // SET sudah auto save ke EEPROM (sesuai firmware bos)
            // Jadi SAVE di sini cuma backup .txt ke HP
            saveMapToPhone();
            status("Backup .txt ke HP - EEPROM sudah auto save pas SET");
            Toast.makeText(getContext(),"Backup .txt - EEPROM auto save pas tap SET",Toast.LENGTH_SHORT).show();
        });
        sendBtn.setOnClickListener(v -> {
            String cmd = sendText.getText().toString();
            // Kalau user ketik GET di bawah, otomatis STOP dulu
            if(cmd.trim().toUpperCase().equals("$GET")) {
                send("Q");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    send("$GET");
                }, 300);
            } else {
                send(cmd);
            }
        });
        return view;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimetypes = {"text/plain", "text/*", "application/octet-stream"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimetypes);
        try { loadFileLauncher.launch(Intent.createChooser(intent, "Pilih file MAP .txt 16x16")); } 
        catch(Exception e) { Toast.makeText(getContext(), "File picker tidak tersedia", Toast.LENGTH_SHORT).show(); }
    }

    private void loadMapFromUri(Uri uri) {
        try {
            InputStream is = getActivity().getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder allText = new StringBuilder();
            String line;
            while((line=reader.readLine())!=null) allText.append(line).append("\n");
            reader.close(); is.close();
            String content = allText.toString();
            int[][] newMap = new int[16][16];
            int r=0; boolean found=false;

            int advIdx = content.indexOf("ADV_MAP");
            if(advIdx != -1) {
                String sub = content.substring(advIdx);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(sub);
                java.util.ArrayList<Integer> nums = new java.util.ArrayList<>();
                while(m.find()) { try { nums.add(Integer.parseInt(m.group())); } catch(Exception e){} if(nums.size()>=300) break; }
                java.util.ArrayList<Integer> filtered = new java.util.ArrayList<>();
                for(int n: nums) if(n>=0 && n<=60) filtered.add(n);
                if(filtered.size()>=258 && filtered.get(0)==16 && filtered.get(1)==16) filtered = new java.util.ArrayList<>(filtered.subList(2, filtered.size()));
                if(filtered.size()>=256) { for(int i=0;i<16;i++) for(int j=0;j<16;j++) newMap[i][j]=filtered.get(i*16+j); found=true; r=16; }
            }
            if(!found) {
                r=0; String[] lines = content.split("\n");
                for(String l: lines) {
                    l=l.trim(); if(l.isEmpty()||l.startsWith("#")||l.startsWith("//")) continue;
                    String[] parts = l.split("[,;\\s\\{\\}]+");
                    java.util.ArrayList<Integer> rowVals = new java.util.ArrayList<>();
                    for(String p: parts) { if(p.isEmpty()) continue; try{ int v=Integer.parseInt(p.trim()); if(v>=0&&v<=60) rowVals.add(v); } catch(Exception e){} }
                    if(rowVals.size()>=16) { for(int j=0;j<16;j++) newMap[r][j]=rowVals.get(j); r++; if(r>=16) break; }
                }
                if(r>=10) found=true;
            }
            if(found) {
                for(int i=0;i<16;i++) advMap[i]=Arrays.copyOf(newMap[i],16);
                buildMapTable();
                Toast.makeText(getContext(), "Map loaded: "+uri.getLastPathSegment(), Toast.LENGTH_LONG).show();
                status("Loaded "+r+" rows dari HP");
                new AlertDialog.Builder(getContext()).setTitle("Kirim ke ECU?").setMessage("Map "+r+"x16 berhasil dimuat. Kirim langsung ke ECU?").setPositiveButton("Ya, Kirim", (d,w)-> sendFullMapToEcu()).setNegativeButton("Nanti", null).show();
            } else { Toast.makeText(getContext(), "Format tidak dikenali", Toast.LENGTH_LONG).show(); }
        } catch(Exception e) { Toast.makeText(getContext(), "Gagal baca file: "+e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void sendFullMapToEcu() {
        if(connected!=Connected.True) { Toast.makeText(getContext(),"ECU tidak connected",Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            try {
                for(int r=0;r<16;r++) {
                    for(int c=0;c<16;c++) {
                        String cmd = "$SET,"+r+","+c+","+advMap[r][c];
                        byte[] data = (cmd + "\r\n").getBytes();
                        if(currentPort!=null) currentPort.write(data, 500);
                        Thread.sleep(30);
                    }
                }
                if(getActivity()!=null) getActivity().runOnUiThread(() -> { send("$SAVE"); Toast.makeText(getContext(),"Full map 16x16 terkirim + SAVE",Toast.LENGTH_LONG).show(); });
            } catch(Exception e) { if(getActivity()!=null) getActivity().runOnUiThread(() -> Toast.makeText(getContext(),"Send map error: "+e.getMessage(),Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    private void saveMapToPhone() {
        StringBuilder sb = new StringBuilder();
        sb.append("const int RPM_GRID[16] = {500, 700, 900, 1100, 1300, 1500, 1800, 2200, 2600, 3000, 3500, 4000, 4500, 5000, 5750, 6500};\n\n");
        sb.append("const int MAP_GRID_KPA[16] = {20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 92, 100};\n\n");
        sb.append("// Saved: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("const uint8_t ADV_MAP_DEFAULT[16][16] PROGMEM = { ");
        for(int r=0;r<16;r++) { sb.append("{ "); for(int c=0;c<16;c++) { sb.append(advMap[r][c]); if(c<15) sb.append(", "); } sb.append(" }"); if(r<15) sb.append(", "); }
        sb.append(" };\n\n");
        for(int r=0;r<16;r++) { for(int c=0;c<16;c++) { sb.append(advMap[r][c]); if(c<15) sb.append(","); } sb.append("\n"); }
        pendingSaveContent = sb.toString();
        String fileName = "ADV_MAP_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())+".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain"); intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try { saveFileLauncher.launch(intent); } catch(Exception e) { Toast.makeText(getContext(),"Save file tidak support",Toast.LENGTH_SHORT).show(); }
    }

    private void saveMapToUri(Uri uri, String content) {
        try { OutputStream os = getActivity().getContentResolver().openOutputStream(uri); os.write(content.getBytes()); os.close(); Toast.makeText(getContext(),"Map disimpan: "+uri.getLastPathSegment(),Toast.LENGTH_LONG).show(); status("Saved ke HP: "+uri.getLastPathSegment()); } 
        catch(Exception e) { Toast.makeText(getContext(),"Gagal simpan: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
    }

    @Override public void onStart() { super.onStart(); if(service != null) service.attach(this); else getActivity().startService(new Intent(getActivity(), SerialService.class)); if(initialStart && isResumed()) { initialStart=false; getActivity().runOnUiThread(this::connect); } }
    @Override public void onStop() { stopDirect(); if(service != null && !getActivity().isChangingConfigurations()) service.detach(); super.onStop(); }
    @Override public void onAttach(@NonNull Context context) { super.onAttach(context); try { getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE); } catch(Exception ignored) {} }
    @Override public void onDetach() { stopDirect(); try { if(receiverRegistered) { getActivity().unregisterReceiver(usbPermissionReceiver); receiverRegistered=false; } } catch(Exception ignored) {} try { getActivity().unbindService(this); } catch(Exception ignored) {} super.onDetach(); }
    @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = ((SerialService.SerialBinder) binder).getService(); service.attach(this); if(initialStart && isResumed()) { initialStart=false; getActivity().runOnUiThread(this::connect); } }
    @Override public void onServiceDisconnected(ComponentName name) { service=null; }

    private void stopDirect() {
        stopRead = true;
        connected = Connected.False;
        try { if(readThread!=null) { readThread.interrupt(); readThread=null; } } catch(Exception ignored) {}
        try { if(currentPort!=null) { currentPort.close(); } } catch(Exception ignored) {}
        try { if(currentConnection!=null) { currentConnection.close(); } } catch(Exception ignored) {}
        currentPort=null; currentConnection=null;
    }

    private void connect() {
        try {
            status("connecting... DIRECT");
            connected=Connected.Pending;
            UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
            UsbDevice device=null;
            for(UsbDevice v: usbManager.getDeviceList().values()) if(v.getDeviceId()==deviceId) { device=v; break; }
            if(device==null) for(UsbDevice v: usbManager.getDeviceList().values()) if(UsbSerialProber.getDefaultProber().probeDevice(v)!=null) { device=v; deviceId=v.getDeviceId(); break; }
            if(device==null) { status("no USB devices found - colok OTG"); return; }
            status("found VID:"+String.format("%04X", device.getVendorId())+" PID:"+String.format("%04X", device.getProductId()));
            if(!usbManager.hasPermission(device)) {
                Intent permIntent = new Intent(ACTION_USB_PERMISSION);
                permIntent.setPackage(getActivity().getPackageName());
                int flags = PendingIntent.FLAG_MUTABLE;
                permissionIntent = PendingIntent.getBroadcast(getActivity(),0,permIntent,flags);
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                try { getActivity().registerReceiver(usbPermissionReceiver,filter,Context.RECEIVER_NOT_EXPORTED); receiverRegistered=true; } catch(Exception e){ getActivity().registerReceiver(usbPermissionReceiver,filter); receiverRegistered=true; }
                usbManager.requestPermission(device,permissionIntent);
                status("requesting permission - klik OK di popup");
                return;
            }
            UsbDeviceConnection usbConnection = usbManager.openDevice(device);
            if(usbConnection==null) { status("open failed - permission denied / busy"); return; }
            UsbSerialPort usbSerialPort=null;
            try { usbSerialPort=UsbSerialProber.getDefaultProber().probeDevice(device).getPorts().get(portNum); }
            catch(Exception e){ usbSerialPort=UsbSerialProber.getDefaultProber().probeDevice(device).getPorts().get(0); }
            if(usbSerialPort==null){ status("no driver for device"); return; }
            usbSerialPort.open(usbConnection);
            baudRate = 115200; usbSerialPort.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
            try { usbSerialPort.setDTR(false); } catch(Exception ignored){}
            try { usbSerialPort.setRTS(false); } catch(Exception ignored){}
            currentPort=usbSerialPort; currentConnection=usbConnection; connected=Connected.True; stopRead=false;
            status("CONNECTED CH340 DIRECT "+baudRate);
            readThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                while(!stopRead && connected==Connected.True) {
                    try {
                        int len = currentPort.read(buf, 500);
                        if(len>0) {
                            byte[] data = Arrays.copyOf(buf, len);
                            String chunk = new String(data);
                            buffer += chunk;
                            if(chunk.contains("MAPR") || chunk.contains("MAPDONE")) {
                                String dbg = chunk.replace("\r"," ").replace("\n"," ");
                                if(getActivity()!=null) getActivity().runOnUiThread(()->{ if(receiveText!=null) receiveText.append("\n[RAW] "+dbg+"\n"); });
                            }
                            while(buffer.contains("*")) {
                                int idx = buffer.indexOf("*");
                                if(idx<0) break;
                                String line = buffer.substring(0, idx+1);
                                buffer = buffer.substring(idx+1);
                                line=line.trim();
                                if(line.isEmpty()) continue;
                                if(line.contains("$MAPR")) line = line.substring(line.indexOf("$MAPR"));
                                else if(line.contains("$MAPDONE")) line = line.substring(line.indexOf("$MAPDONE"));
                                else if(line.contains("$OK") && line.lastIndexOf("$OK")>0) line = line.substring(line.lastIndexOf("$OK"));
                                String finalLine=line;
                                if(getActivity()!=null) getActivity().runOnUiThread(()->handleLine(finalLine));
                            }
                        }
                    } catch(Exception e) { if(!stopRead && getActivity()!=null) getActivity().runOnUiThread(()->status("read error: "+e.getMessage())); break; }
                }
            });
            readThread.start();
        } catch(Exception e){ status("connection failed: "+e.getMessage()); e.printStackTrace(); stopDirect(); }
    }

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if(ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized(this) {
                    UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted=intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false);
                    if(granted && device!=null) connect(); else status("permission denied");
                    try{ if(receiverRegistered){ context.unregisterReceiver(this); receiverRegistered=false; } }catch(Exception ignored){}
                }
            }
        }
    };

    @Override public void onSerialConnect() { status("connected"); connected=Connected.True; }
    @Override public void onSerialConnectError(Exception e){ status("connection failed: "+e.getMessage()); disconnect(); }
    @Override public void onSerialRead(byte[] data){ onSerialRead(new ArrayDeque<>(Arrays.asList(data))); }
    @Override public void onSerialRead(ArrayDeque<byte[]> datas) {
        for(byte[] data: datas){
            String chunk=new String(data); buffer+=chunk;
            while(buffer.contains("*")){
                int idx=buffer.indexOf("*"); if(idx<0) break;
                String line=buffer.substring(0,idx+1); buffer=buffer.substring(idx+1);
                line=line.trim(); if(line.isEmpty()) continue;
                if(line.contains("$MAPR")) line=line.substring(line.indexOf("$MAPR"));
                String finalLine=line; if(getActivity()!=null) getActivity().runOnUiThread(()->handleLine(finalLine));
            }
        }
    }
    @Override public void onSerialIoError(Exception e){ status("IO error: "+e.getMessage()); }
    public void status(String str){
        if(statusText!=null && getActivity()!=null) getActivity().runOnUiThread(()->{
            if(str.toLowerCase().contains("connected")){ statusText.setText("CONNECTED"); statusText.setTextColor(Color.parseColor("#00FF88")); }
            else if(str.toLowerCase().contains("failed")||str.toLowerCase().contains("denied")||str.toLowerCase().contains("no usb")){ statusText.setText(str.toUpperCase()); statusText.setTextColor(Color.RED); }
            else statusText.setText(str.toUpperCase());
        });
        if(receiveText!=null){ if(getActivity()!=null) getActivity().runOnUiThread(()->receiveText.append("\n[STATUS] "+str+"\n")); else receiveText.append("\n[STATUS] "+str+"\n"); }
    }
    private void disconnect(){ stopDirect(); if(service!=null) try{ service.disconnect(); } catch(Exception ignored){} service=null; }


    // ===== LOG SYSTEM 1 DETIK SEBELUM + SESUDAH - 100% AMAN =====
    private void doLog(){
        if(getContext()==null) return;
        int rIdx=0,cIdx=0;
        for(int i=0;i<15;i++) if(currentMap>=MAP_GRID[i] && currentMap<MAP_GRID[i+1]){rIdx=i; break;}
        for(int i=0;i<15;i++) if(currentRpm>=RPM_GRID[i] && currentRpm<RPM_GRID[i+1]){cIdx=i; break;}
        if(currentRpm>=RPM_GRID[15]) cIdx=15;
        if(currentMap>=MAP_GRID[15]) rIdx=15;
        final int fr=rIdx, fc=cIdx;
        EditText input=new EditText(getContext());
        input.setHint("Misal: Ngempos, Ngelitik, Enak");
        new AlertDialog.Builder(getContext()).setTitle("LOG @ RPM "+currentRpm+" MAP "+currentMap+" ADV "+currentAdv+"Â°")
            .setMessage("Cell: Row "+fr+" Col "+fc+" ["+MAP_GRID[fr]+"kPa / "+RPM_GRID[fc]+" RPM] = "+advMap[fr][fc]+"Â°\n\nAkan simpan 1 detik SEBELUM + SESUDAH klik")
            .setView(input)
            .setPositiveButton("SIMPAN LOG",(d,w)->{
                String note=input.getText().toString();
                if(note.trim().isEmpty()) note="Aneh";
                startLogWithPrePost(note, fr, fc);
            })
            .setNegativeButton("Batal",null)
            .setNeutralButton("Quick LOG",(d,w)-> quickLog())
            .show();
    }
    
    private void quickLog(){
        int rIdx=0,cIdx=0;
        for(int i=0;i<15;i++) if(currentMap>=MAP_GRID[i] && currentMap<MAP_GRID[i+1]){rIdx=i; break;}
        for(int i=0;i<15;i++) if(currentRpm>=RPM_GRID[i] && currentRpm<RPM_GRID[i+1]){cIdx=i; break;}
        if(currentRpm>=RPM_GRID[15]) cIdx=15;
        if(currentMap>=MAP_GRID[15]) rIdx=15;
        startLogWithPrePost("Quick", rIdx, cIdx);
    }
    
    private void startLogWithPrePost(String note, int r, int c){
        pendingNote = note;
        pendingRow=r; pendingCol=c;
        logTriggerTime = System.currentTimeMillis();
        pendingPost.clear();
        waitingPost = true;
        status("LOG trigger @ "+currentRpm+" RPM - merekam 1 detik sesudah...");
        Toast.makeText(getContext(),"LOG trigger - tunggu 1 detik sesudah",Toast.LENGTH_SHORT).show();
        // Jika history kosong, langsung finish
        if(history.size()<2){
            // tetap tunggu 1 detik
        }
    }
    
    private void finishLogWithPost(){
        waitingPost = false;
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(logTriggerTime));
        logCount++;
        StringBuilder entry = new StringBuilder();
        entry.append(String.format(Locale.US, "\n=== LOG #%d @ %s - %s ===\n", logCount, time, pendingNote));
        entry.append(String.format(Locale.US, "Trigger: RPM %d MAP %d ADV %dÂ° | Row %d Col %d [%dkPa / %dRPM = %dÂ°]\n", 
            currentRpm, currentMap, currentAdv, pendingRow, pendingCol, MAP_GRID[pendingRow], RPM_GRID[pendingCol], advMap[pendingRow][pendingCol]));
        entry.append("--- 1 DETIK SEBELUM (pre-trigger) ---\n");
        long cutoffBefore = logTriggerTime - 1000;
        int preCount=0;
        for(Sample s: history){
            if(s.t >= cutoffBefore && s.t <= logTriggerTime){
                entry.append(String.format(Locale.US, "  %dms: RPM %d MAP %d ADV %d\n", (int)(s.t-logTriggerTime), s.rpm, s.map, s.adv));
                preCount++;
            }
        }
        if(preCount==0) entry.append("  (tidak ada data sebelum)\n");
        entry.append("--- SAAT KLIK ---\n");
        entry.append(String.format(Locale.US, "  0ms: RPM %d MAP %d ADV %d <== TRIGGER\n", currentRpm, currentMap, currentAdv));
        entry.append("--- 1 DETIK SESUDAH (post-trigger) ---\n");
        if(pendingPost.size()==0) entry.append("  (tidak ada data sesudah)\n");
        else {
            for(Sample s: pendingPost){
                entry.append(String.format(Locale.US, "  +%dms: RPM %d MAP %d ADV %d\n", (int)(s.t-logTriggerTime), s.rpm, s.map, s.adv));
            }
        }
        entry.append(String.format(Locale.US, "=== END LOG #%d ===\n", logCount));
        logBuffer.append(entry.toString());
        if(logText!=null) logText.setText(logBuffer.toString());
        status("LOG #"+logCount+" lengkap 1 detik sebelum+sesudah disimpan");
        Toast.makeText(getContext(),"LOG #"+logCount+" lengkap disimpan",Toast.LENGTH_LONG).show();
        saveLogToFile();
        pendingPost.clear();
    }
    
    
    private void saveLogToFile(){
        try{
            File dir = getContext().getExternalFilesDir(null);
            if(dir==null) return;
            File f = new File(dir, "taruna_log.txt");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(logBuffer.toString().getBytes());
            fos.close();
        }catch(Exception e){}
    }
    
    private void shareLog(){
        try{
            String content = logBuffer.toString();
            // Save backup
            saveLogToFile();
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, content);
            share.putExtra(Intent.EXTRA_SUBJECT, "TARUNA LOG "+new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            startActivity(Intent.createChooser(share, "Share LOG"));
            status("LOG di-share - file taruna_log.txt di ExternalFilesDir");
        }catch(Exception e){ Toast.makeText(getContext(),"Share fail: "+e.getMessage(),Toast.LENGTH_SHORT).show(); }
    }
    
    private void clearLog(){
        logBuffer.setLength(0);
        logBuffer.append("// TARUNA LOG - Aman di RPM tinggi, tidak kirim ke ECU\n");
        logCount=0;
        if(logText!=null) logText.setText(logBuffer.toString());
        saveLogToFile();
        Toast.makeText(getContext(),"LOG cleared",Toast.LENGTH_SHORT).show();
    }
    // ===== END LOG SYSTEM =====

    private void buildMapTable(){

        if(mapTableContainer==null||getContext()==null) return;
        mapTableContainer.removeAllViews();
        LinearLayout header=new LinearLayout(getContext()); header.setOrientation(LinearLayout.HORIZONTAL);
        TextView corner=new TextView(getContext()); corner.setText("MAP/RPM "); corner.setWidth(160); corner.setTextColor(Color.GRAY); corner.setTextSize(9); header.addView(corner);
        for(int c=0;c<16;c++){ TextView tv=new TextView(getContext()); tv.setText(String.valueOf(RPM_GRID[c])); tv.setWidth(90); tv.setTextColor(Color.GRAY); tv.setTextSize(9); header.addView(tv); }
        mapTableContainer.addView(header);
        for(int r=0;r<16;r++){
            LinearLayout row=new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label=new TextView(getContext()); label.setText(MAP_GRID[r]+"kPa "); label.setWidth(160); label.setTextColor(Color.CYAN); label.setTextSize(10); row.addView(label);
            for(int c=0;c<16;c++){
                TextView cell=new TextView(getContext()); cell.setText(String.valueOf(advMap[r][c])); cell.setWidth(90); cell.setHeight(70); cell.setTextColor(Color.WHITE); cell.setTextSize(11); cell.setGravity(17); cell.setPadding(4,4,4,4);
                int val=advMap[r][c]; if(val==5) cell.setBackgroundColor(Color.parseColor("#333333")); else if(val<13) cell.setBackgroundColor(Color.parseColor("#1A3A2A")); else if(val<20) cell.setBackgroundColor(Color.parseColor("#2A5A1A")); else if(val<27) cell.setBackgroundColor(Color.parseColor("#8A6A00")); else cell.setBackgroundColor(Color.parseColor("#8A2A00"));
                final int fr=r,fc=c; cell.setOnClickListener(v->showEditDialog(fr,fc)); row.addView(cell);
            }
            mapTableContainer.addView(row);
        }
    }

    private void showEditDialog(int r,int c){
        if(getContext()==null) return;
        EditText input=new EditText(getContext()); 
        input.setText(String.valueOf(advMap[r][c])); 
        input.setInputType(2);
        input.selectAll();
        new AlertDialog.Builder(getContext()).setTitle("Edit ADV ["+MAP_GRID[r]+"kPa , "+RPM_GRID[c]+"RPM] - SET=SAVE")
            .setMessage("Row "+r+" Col "+c+" = "+MAP_GRID[r]+"kPa / "+RPM_GRID[c]+"RPM\nSET akan otomatis SAVE ke EEPROM kayak tool lama")
            .setView(input)
            .setPositiveButton("SET+SAVE",(d,w)->{ 
                try{ 
                    int v=Integer.parseInt(input.getText().toString()); 
                    if(v>=0&&v<=50){ 
                        advMap[r][c]=v; 
                        buildMapTable(); 
                        // FIX BUG 1: Kirim SET lalu SAVE kayak tool lama bos
                        send("$SET,"+r+","+c+","+v);
                        status("SET Row "+r+" Col "+c+" = "+v+"Â° dikirim...");
                        // Delay 200ms lalu kirim SAVE biar EEPROM kesimpen (firmware lama butuh ini)
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            send("$SAVE");
                            status("SAVE dikirim - Cell Row "+r+" Col "+c+" tersimpan permanen di EEPROM");
                            Toast.makeText(getContext(),"SET+SAVE Row "+r+" Col "+c+" = "+v+"Â° tersimpan!", Toast.LENGTH_SHORT).show();
                        }, 300);
                    } 
                }catch(Exception e){
                    Toast.makeText(getContext(),"Input error: "+e.getMessage(),Toast.LENGTH_SHORT).show();
                } 
            })
            .setNeutralButton("SET saja (RAM)",(d,w)->{
                try{ 
                    int v=Integer.parseInt(input.getText().toString()); 
                    if(v>=0&&v<=50){ 
                        advMap[r][c]=v; 
                        buildMapTable(); 
                        send("$SET,"+r+","+c+","+v);
                        status("SET RAM only Row "+r+" Col "+c+" = "+v+"Â° - hilang pas mati");
                        Toast.makeText(getContext(),"SET RAM only (tidak permanen)",Toast.LENGTH_SHORT).show();
                    } 
                }catch(Exception e){}
            })
            .setNegativeButton("Batal",null).show();
    }

    private void send(String s){
        String orig = s.trim().replace("\r","").replace("\n","");
        if(orig.isEmpty()) return;
        if(receiveText!=null) {
            receiveText.append("\n[TRY TX] "+orig+"\n");
        }
        if(connected!=Connected.True) {
            if(currentPort==null && service==null) {
                Toast.makeText(getContext(),"Not connected - port null",Toast.LENGTH_LONG).show(); 
                return;
            }
        }
        buffer = "";
        new Thread(() -> {
            try{
                String toSend;
                if(orig.equals("$GET")) {
                    toSend = "$GET\r\n"; // CR+LF sesuai USB Terminal yang berhasil
                } else if(orig.equals("$SAVE")) {
                    toSend = "$SAVE\r\n";
                } else if(orig.startsWith("$SET")) {
                    toSend = orig + "\r\n";
                } else {
                    toSend = orig;
                    if(!toSend.endsWith("\r\n")) toSend = toSend + "\r\n";
                }
                // Build pendingSaveContent for txt backup
                StringBuilder sb = new StringBuilder();
                sb.append("// TARUNA MAP BACKUP\n");
                for(int rr=0;rr<16;rr++){ for(int cc=0;cc<16;cc++){ sb.append(advMap[rr][cc]); if(cc<15) sb.append(","); } sb.append("\n"); }
                pendingSaveContent = sb.toString();
                byte[] bytes = toSend.getBytes();
                if(getActivity()!=null) getActivity().runOnUiThread(()->{
                    if(receiveText!=null) receiveText.append("[TX] "+bytes.length+" bytes charDelay 5ms\n");
                });
                if(currentPort!=null) {
                    // DIRECT MODE - seperti GET yang berhasil
                    for(int i=0;i<bytes.length;i++) {
                        byte[] one = new byte[]{bytes[i]};
                        currentPort.write(one, 300);
                        Thread.sleep(5);
                    }
                } else if(service!=null) {
                    service.write(bytes);
                }
                Thread.sleep(25); // Line delay 25ms
                if(getActivity()!=null) getActivity().runOnUiThread(()->{
                    if(receiveText!=null) receiveText.append("[TX OK] "+orig+" CR+LF delay OK\n");
                    status(orig+" DIKIRIM - TUNGGU BALASAN...");
                });
            }catch(Exception e){
                if(getActivity()!=null) getActivity().runOnUiThread(()->{
                    if(receiveText!=null) receiveText.append("[TX FAIL] "+e.getMessage()+"\n");
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void handleLine(String line){
        if(receiveText!=null){ receiveText.append(line+"\n"); if(receiveText.getText().length()>10000) receiveText.setText(receiveText.getText().subSequence(receiveText.getText().length()-8000,receiveText.getText().length())); }
        String clean = line.replace("*","").trim();
        if(clean.startsWith("$OK,RPM,") || (clean.contains("RPM,") && clean.contains("MAP,"))){
            try{
                String tmp = clean.replace("$OK,","").replace("$",""); String[] p=tmp.split(","); 
                for(int i=0;i<p.length-1;i++){ if(p[i].trim().equals("RPM")) currentRpm=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("MAP")) currentMap=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("ADV")) currentAdv=Integer.parseInt(p[i+1].trim()); }
                if(rpmValue!=null) rpmValue.setText(String.valueOf(currentRpm)); if(rpmBar!=null) rpmBar.setProgress(Math.min(currentRpm,6500)); if(mapValue!=null) mapValue.setText(currentMap+" kPa"); if(advValue!=null) advValue.setText(currentAdv+"Â°");
                // Simpan ke blackbox history - aman, cuma RAM HP
                long nowMs = System.currentTimeMillis();
                history.addLast(new Sample(nowMs, currentRpm, currentMap, currentAdv));
                while(history.size()>30) history.removeFirst(); // keep 7.5 detik
                // Kalau lagi nunggu 1 detik sesudah LOG
                if(waitingPost){
                    pendingPost.addLast(new Sample(nowMs, currentRpm, currentMap, currentAdv));
                    if(nowMs - logTriggerTime >= 1000){
                        // Selesai 1 detik sesudah, tulis log lengkap
                        finishLogWithPost();
                    }
                }
                if(limiterText!=null){ if(currentRpm>=6500) limiterText.setText("LIMITER: ACTIVE!"); else if(currentRpm>=6200) limiterText.setText("LIMIT: CUTBACK"); else limiterText.setText("LIMIT: NORMAL 6500"); }
                if(dfcoText!=null){ if(currentMap<30 && currentRpm>1600) dfcoText.setText("DFCO: ON"); else dfcoText.setText("DFCO: OFF"); }
                if(liveCellInfo!=null){ int rIdx=0,cIdx=0; for(int i=0;i<15;i++) if(currentMap>=MAP_GRID[i] && currentMap<MAP_GRID[i+1]){rIdx=i; break;} for(int i=0;i<15;i++) if(currentRpm>=RPM_GRID[i] && currentRpm<RPM_GRID[i+1]){cIdx=i; break;} liveCellInfo.setText("Live Cell: RPM "+RPM_GRID[cIdx]+" / MAP "+MAP_GRID[rIdx]+" kPa -> Row "+rIdx+" Col "+cIdx+" = "+advMap[rIdx][cIdx]+"Â° | Real ADV: "+currentAdv+"Â°"); }
            }catch(Exception e){}
        } else if(clean.startsWith("$GRIDR,")){
            try{
                String[] p=clean.replace("$GRIDR,","").replace("*","").split(",");
                if(p.length>=16){ for(int i=0;i<16;i++) RPM_GRID[i]=Integer.parseInt(p[i].trim()); }
                status("GRID RPM auto update dari ECU baseline");
                if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable());
            }catch(Exception e){}
        } else if(clean.startsWith("$GRIDM,")){
            try{
                String[] p=clean.replace("$GRIDM,","").replace("*","").split(",");
                if(p.length>=16){ for(int i=0;i<16;i++) MAP_GRID[i]=Integer.parseInt(p[i].trim()); }
                status("GRID MAP auto update dari ECU baseline");
                if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable());
            }catch(Exception e){}
        } else if(clean.contains("GRIDDONE")){
            status("GRID DONE - Auto adjust selesai");
            buildMapTable();
        } else if(clean.startsWith("$MAPR,") || clean.startsWith("MAPR,") ){
            try{ String t = clean.replace("$MAPR,","").replace("MAPR,",""); String[] p=t.split(","); int row=Integer.parseInt(p[0].trim()); for(int c=0;c<16 && c+1<p.length;c++) advMap[row][c]=Integer.parseInt(p[c+1].trim()); buildMapTable(); status("MAP Row "+row+" diterima"); if(row==15) { Toast.makeText(getContext(),"Full MAP 16x16 diterima!",Toast.LENGTH_LONG).show(); status("FULL MAP DITERIMA 16x16"); } }catch(Exception e){ status("MAPR parse error: "+e.getMessage()); }
        } else if(clean.contains("EEPROM")){ Toast.makeText(getContext(),"Cell saved to EEPROM! Aman matikan ECU",Toast.LENGTH_SHORT).show(); status("EEPROM SAVED - Tidak hilang pas mati"); } else if(clean.contains("MAPDONE")){ Toast.makeText(getContext(),"MAPDONE - Full map + GRID diterima!",Toast.LENGTH_LONG).show(); status("MAPDONE - FULL MAP + GRID DITERIMA - Baseline V7.4 Auto"); if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable()); }
        else if(clean.contains("SAVE_DONE")||clean.contains("$OK,SAVE")||clean.toUpperCase().contains("SAVE OK")||clean.toUpperCase().contains("EEPROM OK")){ Toast.makeText(getContext(),"Map Saved to EEPROM!",Toast.LENGTH_LONG).show(); status("SAVE DONE - EEPROM OK"); }
    }
}
