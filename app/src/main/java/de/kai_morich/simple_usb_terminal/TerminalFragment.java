
package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
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
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.util.ArrayDeque;
import java.util.Arrays;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    // V17 DIRECT fields - bypass SerialService for easy connect like original USB Terminal
    private com.hoho.android.usbserial.driver.UsbSerialPort directPort = null;
    private android.hardware.usb.UsbDeviceConnection directConnection = null;
    private boolean directConnected = false;


    private enum Connected { False, Pending, True }
    private Connected connected = Connected.False;
    private String deviceAddress;
    private SerialService service;
    private boolean initialStart = true;

    private final int[] RPM_GRID = {500,700,900,1100,1300,1500,1800,2200,2600,3000,3500,4000,4500,5000,5750,6500};
    private final int[] MAP_GRID = {20,25,30,35,40,45,50,55,60,65,70,75,80,85,92,100};
    
    private TextView receiveText, rpmValue, mapValue, advValue, statusText, limiterText, dfcoText, liveCellInfo;
    private ProgressBar rpmBar;
    private EditText sendText;
    private LinearLayout mapTableContainer;
    private Button btnGet, btnSave, btnLoad, sendBtn;
    
    private int[][] advMap = new int[16][16];
    private int currentRpm = 0, currentMap = 45, currentAdv = 16;
    private String rxBuffer = "";
    private String buffer = "";

    // === DFCO DYNAMIC CONFIG - AUTO FROM ECU ===
    private int dfcoRpmOn = 1700;
    private int dfcoRpmOff = 1400;
    private int dfcoMapOn = 25;
    private int dfcoMapOff = 30;
    private boolean dfcoLogicOr = true; // true=OR (karbu), false=AND (injeksi)
    private boolean dfcoActiveState = false;
    private boolean dfcoConfigReceived = false;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        if(getArguments() != null) {
            try {
                deviceAddress = getArguments().getString("device");
                if(deviceAddress == null) {
                    Object dev = getArguments().get("device");
                    if(dev != null) deviceAddress = dev.toString();
                }
            } catch(Exception e) { deviceAddress = null; }
        }
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

                int[][] defaultMap = {
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5},
          {5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5}
        };

        for(int i=0;i<16;i++) advMap[i]=Arrays.copyOf(defaultMap[i],16);
        buildMapTable();


        loadDfcoConfigFromPrefs();
        btnGet.setOnClickListener(v -> {
            rxBuffer = "";
            send("$GET*\n");
            send("$GET\n");
            send("$DFCO_CFG?\n"); // minta config DFCO dari ECU
            status("GET map from ECU... tunggu $MAPR 0-15");
        });

                btnSave.setOnClickListener(v -> {
            send("$SAVE*\n");
            send("$SAVE\n");
            send("$BURN*\n");
            send("$BURN\n");
            try {
                StringBuilder cHeader = new StringBuilder();
                cHeader.append("const int RPM_GRID[16] = {500, 700, 900, 1100, 1300, 1500, 1800, 2200, 2600, 3000, 3500, 4000, 4500, 5000, 5750, 6500}; \n");
                cHeader.append("\n");
                cHeader.append("const int MAP_GRID_KPA[16] = {20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 92, 100};\n");
                cHeader.append("\n");
                cHeader.append("const uint8_t ADV_MAP_DEFAULT[16][16] PROGMEM = {");
                for(int r=0;r<16;r++) {
                    cHeader.append(" { ");
                    for(int c=0;c<16;c++) {
                        cHeader.append(String.valueOf(advMap[r][c]));
                        if(c<15) cHeader.append(", ");
                    }
                    cHeader.append(" }");
                    if(r<15) cHeader.append(", ");
                }
                cHeader.append(" };\n");
                java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                String fileName = "TarunaMap_" + ts + ".txt";
                java.io.File file = new java.io.File(downloadDir, fileName);
                java.io.FileWriter writer = new java.io.FileWriter(file);
                writer.write(cHeader.toString());
                writer.close();
                android.content.SharedPreferences prefs = getActivity().getSharedPreferences("taruna_map", android.content.Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor ed = prefs.edit();
                for(int r=0;r<16;r++) {
                    StringBuilder sb = new StringBuilder();
                    for(int c=0;c<16;c++) sb.append(advMap[r][c]).append(",");
                    ed.putString("row_"+r, sb.toString());
                }
                ed.apply();
                Toast.makeText(getContext(), "Saved ECU + " + fileName, Toast.LENGTH_LONG).show();
                status("Saved: " + file.getAbsolutePath() + " (siap flash)");
            } catch(Exception e) {
                status("save error: " + e.getMessage());
            }
        });

        btnLoad.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Load Map dari?");
            String[] options = {"Backup HP (internal)", "Browse File .txt di HP"};
            builder.setItems(options, (dialog, which) -> {
                if(which==0) {
                    try {
                        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("taruna_map", android.content.Context.MODE_PRIVATE);
                        if(prefs.contains("row_0")) {
                            for(int r=0;r<16;r++) {
                                String row = prefs.getString("row_"+r, null);
                                if(row!=null) {
                                    String[] vals = row.split(",");
                                    for(int c=0;c<16 && c<vals.length;c++) {
                                        try { advMap[r][c]=Integer.parseInt(vals[c].trim()); } catch(Exception ex){}
                                    }
                                }
                            }
                            buildMapTable();
                            Toast.makeText(getContext(), "Loaded backup", Toast.LENGTH_SHORT).show();
                            status("Loaded backup HP");
                        } else {
                            Toast.makeText(getContext(), "No backup found", Toast.LENGTH_SHORT).show();
                        }
                    } catch(Exception e){}
                } else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    String[] mimeTypes = {"text/plain", "text/*", "application/octet-stream"};
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    try {
                        startActivityForResult(intent, 1001);
                    } catch(Exception e) {
                        Intent intent2 = new Intent(Intent.ACTION_GET_CONTENT);
                        intent2.setType("*/*");
                        startActivityForResult(intent2, 1001);
                    }
                }
            });
            builder.show();
        });

        sendBtn.setOnClickListener(v -> {
            String cmd = sendText.getText().toString();
            if(!cmd.endsWith("\n")) cmd+="\n";
            send(cmd);
        });
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null) service.attach(this);
        else getActivity().startService(new Intent(getActivity(), SerialService.class));
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations()) service.detach();
        super.onStop();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) { service = null; }

    // V7 - Don't call service.connect() - MainActivity handles it, we only request permission

    // V17 DIRECT CONNECT - same as original USB Terminal easy connect
    private void connect() {
        try {
            status("connecting... V17 DIRECT");
            connected = Connected.Pending;
            android.hardware.usb.UsbManager usbManager = (android.hardware.usb.UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
            if(usbManager == null || usbManager.getDeviceList().isEmpty()) { status("no USB - check OTG"); return; }
            for(android.hardware.usb.UsbDevice device : usbManager.getDeviceList().values()) {
                String vidpid = String.format("VID:%04X PID:%04X", device.getVendorId(), device.getProductId());
                status("found " + vidpid);
                if(!usbManager.hasPermission(device)) {
                    Intent permIntent = new Intent("de.kai_morich.simple_usb_terminal.USB_PERMISSION");
                    permIntent.setPackage(getActivity().getPackageName());
                    android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(getActivity(), 0, permIntent, android.app.PendingIntent.FLAG_MUTABLE);
                    usbManager.requestPermission(device, pi);
                    status("requesting permission - tap OK then tap again");
                    return;
                }
                try {
                    directConnection = usbManager.openDevice(device);
                    if(directConnection == null) { status("openDevice failed"); return; }
                    com.hoho.android.usbserial.driver.UsbSerialProber prober = com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber();
                    com.hoho.android.usbserial.driver.UsbSerialDriver driver = prober.probeDevice(device);
                    if(driver == null) {
                        java.util.List<com.hoho.android.usbserial.driver.UsbSerialDriver> all = prober.findAllDrivers(usbManager);
                        if(all.isEmpty()) { status("no driver for CH340"); return; }
                        driver = all.get(0);
                    }
                    java.util.List<com.hoho.android.usbserial.driver.UsbSerialPort> ports = driver.getPorts();
                    if(ports.isEmpty()) { status("no ports"); return; }
                    directPort = ports.get(0);
                    directPort.open(directConnection);
                    directPort.setParameters(115200, 8, 1, 0);
                    status("CONNECTED CH340 DIRECT 115200");
                    // Auto load phone backup if exists
                    try {
                        android.content.SharedPreferences prefs = getActivity().getSharedPreferences("taruna_map", android.content.Context.MODE_PRIVATE);
                        if(prefs.contains("row_0")) {
                            for(int r=0;r<16;r++) {
                                String row = prefs.getString("row_"+r, null);
                                if(row != null) {
                                    String[] vals = row.split(",");
                                    for(int c=0;c<16 && c<vals.length;c++) { try { advMap[r][c] = Integer.parseInt(vals[c]); } catch(Exception ex) {} }
                                }
                            }
                            buildMapTable();
                            status("Loaded backup map from phone");
                        }
                    } catch(Exception ex) {}
                    connected = Connected.True;
                    directConnected = true;
                    startDirectReadThread();
                    new android.os.Handler().postDelayed(() -> {
                        try { if(directPort != null) directPort.write("$GET\n".getBytes(), 500); } catch(Exception ex) {}
                    }, 500);
                    return;
                } catch(Exception ex) {
                    status("direct error: " + ex.toString());
                    return;
                }
            }
        } catch(Exception ex) { status("fail: " + ex.toString()); }
    }

    private void startDirectReadThread() {
        new Thread(() -> {
            byte[] buf = new byte[4096];
            while(directConnected && directPort != null) {
                try {
                    int len = directPort.read(buf, 1000);
                    if(len > 0) {
                        byte[] data = java.util.Arrays.copyOf(buf, len);
                        if(getActivity() != null) getActivity().runOnUiThread(() -> onDirectData(data));
                    }
                } catch(Exception ex) { break; }
            }
        }).start();
    }

    private void onDirectData(byte[] data) {
        try { onSerialRead(data); } catch(Exception ex) {}
        String s = new String(data);
        if(s.length() < 100) status(s);
    }

    @Override
    public void onSerialConnect() { status("connected"); connected = Connected.True; }
    @Override
    public void onSerialConnectError(Exception e) { status("connection failed: " + e.getMessage()); disconnect(); }
    @Override
    public void onSerialRead(byte[] data) { onSerialRead(new ArrayDeque<>(Arrays.asList(data))); }
    @Override
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        for(byte[] data : datas) {
            String chunk = new String(data);
            buffer += chunk;
            while(buffer.contains("\n") || buffer.contains("*")) {
                String line;
                if(buffer.contains("\n")) {
                    int idx = buffer.indexOf("\n");
                    line = buffer.substring(0, idx+1);
                    buffer = buffer.substring(idx+1);
                } else { line = buffer; buffer = ""; }
                line = line.trim();
                if(line.isEmpty()) continue;
                String finalLine = line;
                if(getActivity()!=null) getActivity().runOnUiThread(() -> handleLine(finalLine));
            }
        }
    }
    @Override
    public void onSerialIoError(Exception e) { status("IO error: " + e.getMessage()); }
    
    public void status(String str) {
        if(getActivity()==null) return;
        getActivity().runOnUiThread(() -> {
            if(statusText != null) {
                if(str.toLowerCase().contains("connected")) { statusText.setText("CONNECTED"); statusText.setTextColor(Color.parseColor("#00FF88")); }
                else if(str.toLowerCase().contains("disconnected")) { statusText.setText("DISCONNECTED"); statusText.setTextColor(Color.RED); }
                else if(str.toLowerCase().contains("connecting")) { statusText.setText("CONNECTING..."); statusText.setTextColor(Color.YELLOW); }
                else { statusText.setText(str.toUpperCase()); }
            }
            if(receiveText != null) {
                receiveText.append("\n[STATUS] " + str + "\n");
            }
        });
    }


    private void disconnect() {
        directConnected = false;
        try { if(directPort != null) directPort.close(); } catch(Exception ex) {}
        try { if(directConnection != null) directConnection.close(); } catch(Exception ex) {}
        directPort = null;
        directConnection = null;
        connected = Connected.False;
        try { if(service != null) service.disconnect(); } catch(Exception ex) {}
        status("DISCONNECTED");
    }
    private void buildMapTable() {
        if(mapTableContainer==null || getContext()==null) return;
        mapTableContainer.removeAllViews();
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView corner = new TextView(getContext()); corner.setText("MAP/RPM "); corner.setWidth(160); corner.setTextColor(Color.GRAY); corner.setTextSize(9);
        header.addView(corner);
        for(int c=0;c<16;c++){ TextView tv=new TextView(getContext()); tv.setText(String.valueOf(RPM_GRID[c])); tv.setWidth(90); tv.setTextColor(Color.GRAY); tv.setTextSize(9); header.addView(tv);}
        mapTableContainer.addView(header);
        for(int r=0;r<16;r++){
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(getContext()); label.setText(MAP_GRID[r]+"kPa "); label.setWidth(160); label.setTextColor(Color.CYAN); label.setTextSize(10);
            row.addView(label);
            for(int c=0;c<16;c++){
                TextView cell = new TextView(getContext());
                cell.setText(String.valueOf(advMap[r][c]));
                cell.setWidth(90); cell.setHeight(70);
                cell.setTextColor(Color.WHITE); cell.setTextSize(11);
                cell.setGravity(17); cell.setPadding(4,4,4,4);
                int val = advMap[r][c];
                if(val<13) cell.setBackgroundColor(Color.parseColor("#1A3A2A"));
                else if(val<20) cell.setBackgroundColor(Color.parseColor("#2A5A1A"));
                else if(val<27) cell.setBackgroundColor(Color.parseColor("#8A6A00"));
                else cell.setBackgroundColor(Color.parseColor("#8A2A00"));
                final int fr=r, fc=c;
                cell.setOnClickListener(v -> showEditDialog(fr,fc));
                row.addView(cell);
            }
            mapTableContainer.addView(row);
        }
    }

    private void showEditDialog(int r, int c){
        if(getContext()==null) return;
        EditText input = new EditText(getContext());
        input.setText(String.valueOf(advMap[r][c]));
        input.setInputType(2);
        new AlertDialog.Builder(getContext())
            .setTitle("Edit ADV ["+MAP_GRID[r]+"kPa , "+RPM_GRID[c]+"RPM] REAL DEG")
            .setView(input)
            .setPositiveButton("SET", (d,w)->{
                try{
                    int v=Integer.parseInt(input.getText().toString());
                    if(v>=0&&v<=50){
                        advMap[r][c]=v;
                        buildMapTable();
                        send("$SET,"+r+","+c+","+v+"\n");
                    }
                }catch(Exception e){}
            }).setNegativeButton("Batal",null).show();
    }


    private void send(String s){
        try {
            if(s == null || s.isEmpty()) return;
            byte[] data = (s + "\n").getBytes();
            // V18 FIX: Use directPort if connected via DIRECT method
            if(directConnected && directPort != null) {
                try {
                    directPort.write(data, 500);
                    status("SENT: " + s);
                    return;
                } catch(Exception e) {
                    status("direct write fail: " + e.getMessage());
                }
            }
            if(service!=null) service.write(data);
            else status("not connected - tap CdcAcm again");
        } catch(Exception e) { status("send failed: " + e.getMessage()); }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==1001 && resultCode==android.app.Activity.RESULT_OK && data!=null) {
            try {
                android.net.Uri uri = data.getData();
                java.io.InputStream is = getActivity().getContentResolver().openInputStream(uri);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
                StringBuilder allText = new StringBuilder();
                String line;
                while((line=reader.readLine())!=null) allText.append(line).append("\n");
                reader.close();
                String content = allText.toString();
                int row=0;
                if(content.contains("ADV_MAP_DEFAULT")) {
                    java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile("\\{\\s*([0-9,\\s]+)\\s*\\}");
                    java.util.regex.Matcher matcher = rowPattern.matcher(content);
                    while(matcher.find() && row<16) {
                        String rowStr = matcher.group(1);
                        String[] vals = rowStr.split("[,\\s]+");
                        java.util.ArrayList<String> clean = new java.util.ArrayList<>();
                        for(String v: vals) if(!v.trim().isEmpty()) clean.add(v.trim());
                        if(clean.size()>=16) {
                            for(int c=0;c<16;c++) try { advMap[row][c]=Integer.parseInt(clean.get(c)); } catch(Exception e){}
                            row++;
                        }
                    }
                } else {
                    String[] lines = content.split("\n");
                    for(String l: lines) {
                        if(row>=16) break;
                        l=l.trim();
                        if(l.isEmpty() || l.startsWith("#") || l.startsWith("//") || l.startsWith("const") || l.startsWith("RPM") || l.startsWith("MAP")) continue;
                        String[] vals = l.split("[,\\s]+");
                        java.util.ArrayList<String> clean = new java.util.ArrayList<>();
                        for(String v: vals) if(!v.trim().isEmpty()) clean.add(v.trim());
                        if(clean.size()<16) continue;
                        for(int c=0;c<16;c++) try { advMap[row][c]=Integer.parseInt(clean.get(c)); } catch(Exception e){}
                        row++;
                    }
                }
                buildMapTable();
                Toast.makeText(getContext(), "Loaded: "+row+" rows", Toast.LENGTH_LONG).show();
                status("Loaded "+row+" rows: "+uri.getLastPathSegment());
            } catch(Exception e) {
                Toast.makeText(getContext(), "Load failed: "+e.getMessage(), Toast.LENGTH_LONG).show();
                status("load error: "+e.getMessage());
            }
        }
    }

    // === DFCO DYNAMIC FROM ECU - APK OTOMATIS IKUT ECU ===
    private void loadDfcoConfigFromPrefs(){
        try{
            android.content.SharedPreferences p = getActivity().getSharedPreferences("dfco_cfg", Context.MODE_PRIVATE);
            dfcoRpmOn = p.getInt("rpmOn", 1700);
            dfcoRpmOff = p.getInt("rpmOff", 1400);
            dfcoMapOn = p.getInt("mapOn", 25);
            dfcoMapOff = p.getInt("mapOff", 30);
            dfcoLogicOr = p.getBoolean("isOr", true);
            dfcoConfigReceived = p.getBoolean("received", false);
        }catch(Exception e){}
    }

    private void saveDfcoConfigToPrefs(){
        try{
            android.content.SharedPreferences p = getActivity().getSharedPreferences("dfco_cfg", Context.MODE_PRIVATE);
            p.edit().putInt("rpmOn", dfcoRpmOn).putInt("rpmOff", dfcoRpmOff).putInt("mapOn", dfcoMapOn).putInt("mapOff", dfcoMapOff).putBoolean("isOr", dfcoLogicOr).putBoolean("received", true).apply();
        }catch(Exception e){}
    }

    private void parseDfcoConfig(String line){
        // Format dari ECU: $DFCO_CFG,1700,1400,25,30,OR*  atau $DFCO_CFG,1700,25,OR*
        try{
            String clean = line.replace("*","").replace("$DFCO_CFG,","").trim();
            String[] parts = clean.split(",");
            if(parts.length >= 2){
                if(parts.length >= 5){
                    // Format lengkap: rpmOn,rpmOff,mapOn,mapOff,logic
                    dfcoRpmOn = Integer.parseInt(parts[0].trim());
                    dfcoRpmOff = Integer.parseInt(parts[1].trim());
                    dfcoMapOn = Integer.parseInt(parts[2].trim());
                    dfcoMapOff = Integer.parseInt(parts[3].trim());
                    dfcoLogicOr = parts[4].trim().equalsIgnoreCase("OR");
                } else if(parts.length == 3){
                    // Format simple: rpmOn,mapOn,logic
                    dfcoRpmOn = Integer.parseInt(parts[0].trim());
                    dfcoRpmOff = dfcoRpmOn - 300;
                    dfcoMapOn = Integer.parseInt(parts[1].trim());
                    dfcoMapOff = dfcoMapOn + 5;
                    dfcoLogicOr = parts[2].trim().equalsIgnoreCase("OR");
                } else if(parts.length == 2){
                    dfcoRpmOn = Integer.parseInt(parts[0].trim());
                    dfcoMapOn = Integer.parseInt(parts[1].trim());
                    dfcoRpmOff = dfcoRpmOn - 300;
                    dfcoMapOff = dfcoMapOn + 5;
                }
                dfcoConfigReceived = true;
                saveDfcoConfigToPrefs();
                if(getActivity()!=null) getActivity().runOnUiThread(()->{
                    status("DFCO CFG dari ECU: RPM_ON="+dfcoRpmOn+" RPM_OFF="+dfcoRpmOff+" MAP_ON="+dfcoMapOn+" MAP_OFF="+dfcoMapOff+" LOGIC="+(dfcoLogicOr?"OR (karbu)":"AND (injeksi)"));
                    if(getContext()!=null) Toast.makeText(getContext(), "DFCO Config Auto: "+dfcoRpmOn+"/"+dfcoMapOn+" "+(dfcoLogicOr?"OR":"AND"), Toast.LENGTH_LONG).show();
                });
            }
        }catch(Exception e){ status("DFCO CFG parse err: "+e.getMessage()+" line:"+line); }
    }

    private boolean evaluateDfco(int rpm, int map){
        // Logika otomatis ikut ECU - tanpa build APK lagi
        if(!dfcoActiveState){
            boolean cond;
            if(dfcoLogicOr){
                cond = (rpm >= dfcoRpmOn || map <= dfcoMapOn);
            } else {
                cond = (rpm >= dfcoRpmOn && map <= dfcoMapOn);
            }
            if(cond) dfcoActiveState = true;
        } else {
            boolean offCond;
            if(dfcoLogicOr){
                // OR logic: OFF kalau RPM rendah DAN MAP tinggi (balik ke langsam)
                offCond = (rpm <= dfcoRpmOff && map > dfcoMapOff);
            } else {
                // AND logic: OFF kalau RPM rendah ATAU MAP tinggi
                offCond = (rpm <= dfcoRpmOff || map > dfcoMapOff);
            }
            if(offCond) dfcoActiveState = false;
        }
        return dfcoActiveState;
    }

    private void handleLine(String line){
        // Auto config dari ECU - ini yang bikin APK otomatis ikut ECU tanpa build
        if(line.contains("$DFCO_CFG,")){
            try{
                String cfg = line;
                if(cfg.contains("$DFCO_CFG,")) cfg = cfg.substring(cfg.indexOf("$DFCO_CFG,"));
                parseDfcoConfig(cfg);
            }catch(Exception e){}
            if(receiveText!=null) receiveText.append(line+"\n");
            return;
        }
        if(line.startsWith("$MAPR,") && !line.contains("*")) {
            rxBuffer = line;
            return;
        }
        if(!rxBuffer.isEmpty()) {
            line = rxBuffer + line;
            rxBuffer = "";
        }
        if(receiveText!=null) {
            receiveText.append(line+"\n");
            if(receiveText.getText().length()>8000) receiveText.setText(receiveText.getText().subSequence(receiveText.getText().length()-6000, receiveText.getText().length()));
        }
        if(line.startsWith("$OK,RPM,") && !dfcoConfigReceived){
            // Auto minta config DFCO sekali pas awal konek
            if(currentRpm==0) send("$DFCO_CFG?\n");
        }
        if(line.startsWith("$OK,RPM,")){
            try{
                line=line.replace("$OK,","").replace("*","");
                String[] p=line.split(",");
                for(int i=0;i<p.length-1;i++){
                    if(p[i].equals("RPM")) currentRpm=Integer.parseInt(p[i+1]);
                    if(p[i].equals("MAP")) currentMap=Integer.parseInt(p[i+1]);
                    if(p[i].equals("ADV")) currentAdv=Integer.parseInt(p[i+1]);
                }
                if(rpmValue!=null) rpmValue.setText(String.valueOf(currentRpm));
                if(rpmBar!=null) rpmBar.setProgress(Math.min(currentRpm,6500));
                if(mapValue!=null) mapValue.setText(currentMap+" kPa");
                if(advValue!=null) advValue.setText(currentAdv+"\u00B0");
                if(limiterText!=null) {
                    if(currentRpm>=6500) limiterText.setText("LIMITER: ACTIVE!");
                    else if(currentRpm>=6200) limiterText.setText("LIMIT: CUTBACK");
                    else limiterText.setText("LIMIT: NORMAL 6500");
                }
                if(dfcoText!=null) {
                    boolean dfco = evaluateDfco(currentRpm, currentMap);
                    dfcoText.setText("DFCO: "+(dfco?"ON":"OFF")+" ["+dfcoRpmOn+"/"+dfcoMapOn+(dfcoLogicOr?" OR":" AND")+"]");
                }
                if(liveCellInfo!=null) {
                    int rIdx=0,cIdx=0;
                    for(int i=0;i<15;i++) if(currentMap>=MAP_GRID[i] && currentMap<MAP_GRID[i+1]) {rIdx=i; break;}
                    for(int i=0;i<15;i++) if(currentRpm>=RPM_GRID[i] && currentRpm<RPM_GRID[i+1]) {cIdx=i; break;}
                    liveCellInfo.setText("Live Cell: RPM "+RPM_GRID[cIdx]+" / MAP "+MAP_GRID[rIdx]+" kPa -> Row "+rIdx+" Col "+cIdx+" = "+advMap[rIdx][cIdx]+"\u00B0 | Real ADV: "+currentAdv+"\u00B0");
                }
            }catch(Exception e){}
        } else if(line.startsWith("$MAPR,")){
            try{
                String clean=line.replace("*","").replace("$MAPR,","");
                String[] p=clean.split(",");
                int row=Integer.parseInt(p[0].trim());
                for(int c=0;c<16 && c+1<p.length;c++) {
                    try { advMap[row][c]=Integer.parseInt(p[c+1].trim()); } catch(Exception e){}
                }
                if(row%2==0 || row==15) buildMapTable();
                if(row==15) {
                    buildMapTable();
                    status("GET DONE - 16 rows loaded");
                    if(getContext()!=null) Toast.makeText(getContext(), "Map ECU loaded!", Toast.LENGTH_SHORT).show();
                }
            }catch(Exception e){ status("MAPR error: "+e.getMessage()); }
        } else if(line.contains("SAVE_DONE")){
            if(getContext()!=null) Toast.makeText(getContext(),"Map Saved to EEPROM!",Toast.LENGTH_SHORT).show();
        }
    }
}
