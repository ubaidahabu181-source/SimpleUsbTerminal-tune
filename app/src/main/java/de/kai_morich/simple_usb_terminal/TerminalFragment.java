package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Environment;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class TerminalFragment extends Fragment {

    private static final String ACTION_USB_PERMISSION = "de.kai_morich.simple_usb_terminal.USB_PERMISSION";
    private UsbSerialPort currentPort;
    private UsbDeviceConnection currentConnection;
    private Thread readThread;
    private boolean stopRead = false;
    private int deviceId = -1;
    private int portNum = 0;
    private PendingIntent permissionIntent;
    private boolean receiverRegistered = false;

    // GRID
    private int[] RPM_GRID = {500,800,1000,1200,1500,1800,2100,2400,2800,3200,3600,4000,4500,5000,5800,6500};
    private int[] MAP_GRID = {20,25,30,35,40,45,50,55,60,65,70,75,80,85,92,100};
    private int[][] advMap = new int[16][16];
    private TextView[][] cellViews = new TextView[16][16];
    private int currentRpm = 0, currentMap = 45, currentAdv = 16;
    private int lastActiveRow=-1, lastActiveCol=-1;
    private String buffer = "";
    private String pendingSaveContent = "";

    // BLACKBOX - FORMAT BARU CUMA RPM,MAP,ADV
    private static class Sample { int rpm,map,adv; long t; Sample(long t,int r,int m,int a){this.t=t;rpm=r;map=m;adv=a;} }
    private final ArrayDeque<Sample> history = new ArrayDeque<>(); // 2 detik sebelum
    private final ArrayDeque<Sample> pendingPost = new ArrayDeque<>(); // 2 detik sesudah
    private boolean waitingPost = false;
    private long logTriggerTime = 0;
    private int logCount = 0;

    // LONG LOG (TAP) - continuous
    private boolean isContinuousLogging = false;
    private File continuousFile = null;
    private FileOutputStream continuousFos = null;
    private String continuousFileName = "";
    private Button btnLog;

    // UI
    private View pageGauges, pageGrid;
    private GaugeView gaugeRpm, gaugeMap, gaugeAdv, gaugeDfco;
    private TextView dfcoLamp, liveCellInfo, statusText;
    private LinearLayout mapTableContainer;
    private Button btnBack, btnGet, btnSave, btnLoad;

    private ActivityResultLauncher<Intent> loadFileLauncher, saveFileLauncher;

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        Bundle args = getArguments();
        if(args!=null){
            deviceId = args.getInt("device", -1);
            portNum = args.getInt("port", 0);
        }
        // AWAL SEL SEMUA ANGKA 5 - SESUAI REQUEST BOS!
        for(int r=0;r<16;r++) for(int c=0;c<16;c++) advMap[r][c]=5;

        loadFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == android.app.Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData();
                if(uri!=null) loadMapFromUri(uri);
            }
        });
        saveFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == android.app.Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData();
                if(uri!=null) saveMapToUri(uri, pendingSaveContent);
            }
        });
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        pageGauges = view.findViewById(R.id.pageGauges);
        pageGrid = view.findViewById(R.id.pageGrid);
        gaugeRpm = view.findViewById(R.id.gaugeRpm);
        gaugeMap = view.findViewById(R.id.gaugeMap);
        gaugeAdv = view.findViewById(R.id.gaugeAdv);
        gaugeDfco = view.findViewById(R.id.gaugeDfco);
        dfcoLamp = view.findViewById(R.id.dfcoLamp);
        liveCellInfo = view.findViewById(R.id.liveCellInfo);
        statusText = view.findViewById(R.id.statusText);
        mapTableContainer = view.findViewById(R.id.mapTableContainer);
        btnBack = view.findViewById(R.id.btnBack);
        btnGet = view.findViewById(R.id.btnGet);
        btnSave = view.findViewById(R.id.btnSave);
        btnLoad = view.findViewById(R.id.btnLoad);
        btnLog = view.findViewById(R.id.btnLog);

        if(btnBack!=null) btnBack.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.VISIBLE); if(pageGrid!=null) pageGrid.setVisibility(View.GONE); });
        if(gaugeAdv!=null) gaugeAdv.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); });
        if(gaugeRpm!=null) gaugeRpm.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); });
        if(gaugeMap!=null) gaugeMap.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); });
        if(btnGet!=null) btnGet.setOnClickListener(v-> send("$GET"));
        if(btnSave!=null) btnSave.setOnClickListener(v-> saveMapToPhone());
        if(btnLoad!=null) btnLoad.setOnClickListener(v-> openFilePicker());
        if(btnLog!=null){
            // TAP SEKALI = Dialog nama file -> START log sampai STOP, save di Download/tune_log/
            btnLog.setOnClickListener(v-> {
                if(isContinuousLogging){
                    stopContinuousLog();
                } else {
                    showContinuousLogDialog();
                }
            });
            // LONG TAP = Fast log 2 detik sebelum + sesudah, langsung save per file di Download/fast_tune_log/
            btnLog.setOnLongClickListener(v->{ doFastLog(); return true; });
        }

        buildMapTable();
        if(pageGauges!=null) pageGauges.setVisibility(View.VISIBLE);
        if(pageGrid!=null) pageGrid.setVisibility(View.GONE);
        updateGauges(currentRpm, currentMap, currentAdv, false);
        return view;
    }

    private void buildMapTable(){
        if(mapTableContainer==null||getContext()==null) return;
        mapTableContainer.removeAllViews();
        LinearLayout header=new LinearLayout(getContext()); header.setOrientation(LinearLayout.HORIZONTAL);
        TextView corner=new TextView(getContext()); corner.setText("MAP/RPM"); corner.setWidth(180); corner.setTextColor(Color.GRAY); header.addView(corner);
        for(int c=0;c<16;c++){ TextView tv=new TextView(getContext()); tv.setText(String.valueOf(RPM_GRID[c])); tv.setWidth(120); tv.setTextColor(Color.GRAY); header.addView(tv); }
        mapTableContainer.addView(header);
        for(int r=0;r<16;r++){
            LinearLayout row=new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label=new TextView(getContext()); label.setText(MAP_GRID[r]+"kPa"); label.setWidth(180); label.setTextColor(Color.CYAN); row.addView(label);
            for(int c=0;c<16;c++){
                TextView cell=new TextView(getContext()); cell.setText(String.valueOf(advMap[r][c])); cell.setWidth(120); cell.setHeight(100); cell.setGravity(17); cell.setPadding(12,12,12,12);
                int v=advMap[r][c]; cell.setTextColor(Color.WHITE);
                if(v==5) cell.setBackgroundColor(Color.parseColor("#333333"));
                else if(v<13) cell.setBackgroundColor(Color.parseColor("#1A3A2A"));
                else if(v<20) cell.setBackgroundColor(Color.parseColor("#2A5A1A"));
                else if(v<27) cell.setBackgroundColor(Color.parseColor("#8A6A00"));
                else cell.setBackgroundColor(Color.parseColor("#8A2A00"));
                final int fr=r,fc=c; cell.setOnClickListener(vw->showEditDialog(fr,fc)); cellViews[r][c]=cell; row.addView(cell);
            }
            mapTableContainer.addView(row);
        }
        lastActiveRow=-1; lastActiveCol=-1; updateActiveHighlight(currentRpm, currentMap);
    }

    private void updateActiveHighlight(int rpm, int mapKpa){
        if(cellViews==null) return;
        int activeRow=0, activeCol=0;
        for(int i=0;i<15;i++){ if(mapKpa>=MAP_GRID[i] && mapKpa<MAP_GRID[i+1]){ activeRow=i; break; } if(mapKpa>=MAP_GRID[15]) activeRow=15; }
        for(int i=0;i<15;i++){ if(rpm>=RPM_GRID[i] && rpm<RPM_GRID[i+1]){ activeCol=i; break; } if(rpm>=RPM_GRID[15]) activeCol=15; }
        if(activeRow==lastActiveRow && activeCol==lastActiveCol) return;
        try{
            if(lastActiveRow>=0 && lastActiveCol>=0){
                TextView old=cellViews[lastActiveRow][lastActiveCol];
                if(old!=null){ int v=advMap[lastActiveRow][lastActiveCol]; old.setText(String.valueOf(v)); old.setTextColor(Color.WHITE);
                    if(v==5) old.setBackgroundColor(Color.parseColor("#333333"));
                    else if(v<13) old.setBackgroundColor(Color.parseColor("#1A3A2A"));
                    else if(v<20) old.setBackgroundColor(Color.parseColor("#2A5A1A"));
                    else if(v<27) old.setBackgroundColor(Color.parseColor("#8A6A00"));
                    else old.setBackgroundColor(Color.parseColor("#8A2A00"));
                }
            }
            TextView cur=cellViews[activeRow][activeCol];
            if(cur!=null){
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(Color.parseColor("#FFEB3B")); gd.setStroke(5, Color.parseColor("#FF3D00")); gd.setCornerRadius(6);
                cur.setBackground(gd); cur.setTextColor(Color.BLACK);
            }
            lastActiveRow=activeRow; lastActiveCol=activeCol;
            final int fRow = activeRow; final int fCol = activeCol; final int fAdv = currentAdv;
            if(liveCellInfo!=null && getActivity()!=null) getActivity().runOnUiThread(()->liveCellInfo.setText("Live Cell: RPM "+RPM_GRID[fCol]+" / MAP "+MAP_GRID[fRow]+" kPa -> Row "+fRow+" Col "+fCol+" = "+advMap[fRow][fCol]+" deg | Real ADV: "+fAdv+" deg"));
        }catch(Exception e){}
    }

    private void updateGauges(int rpm, int map, int adv, boolean dfco){
        currentRpm=rpm; currentMap=map; currentAdv=adv;
        if(getActivity()!=null) getActivity().runOnUiThread(()->{
            try{
                if(gaugeRpm!=null) gaugeRpm.setValue(rpm, "RPM", rpm+" RPM", 0, 7000);
                if(gaugeMap!=null) gaugeMap.setValue(map, "MAP", map+" kPa", 0, 100);
                if(gaugeAdv!=null) gaugeAdv.setValue(adv, "ADV", adv+" deg", 0, 50);
                if(gaugeDfco!=null){
                    int dfcoVal = dfco ? 100 : 0;
                    gaugeDfco.setValue(dfcoVal, "DFCO", dfco?"ON":"OFF", 0, 100);
                }
                if(dfcoLamp!=null){
                    dfcoLamp.setText(dfco?"DFCO ON":"DFCO OFF");
                    dfcoLamp.setTextColor(dfco?Color.parseColor("#00FFFF"):Color.parseColor("#555555"));
                    try{ dfcoLamp.setBackgroundResource(dfco?R.drawable.bg_dfco_on:R.drawable.bg_dfco_off); }catch(Exception ex){ dfcoLamp.setBackgroundColor(dfco?Color.parseColor("#00FFAA"):Color.parseColor("#333333")); }
                }
                if(btnLog!=null){
                    if(isContinuousLogging){
                        btnLog.setText("STOP LOG");
                        btnLog.setBackgroundColor(Color.RED);
                    } else {
                        btnLog.setText("LOG");
                        btnLog.setBackgroundColor(Color.parseColor("#2196F3"));
                    }
                }
                updateActiveHighlight(rpm, map);
            }catch(Exception e){}
        });
        // History buat fast log 2 detik sebelum
        long nowMs = System.currentTimeMillis();
        history.addLast(new Sample(nowMs, rpm, map, adv));
        while(history.size()>40) history.removeFirst();

        if(waitingPost){
            pendingPost.addLast(new Sample(nowMs, rpm, map, adv));
            if(nowMs - logTriggerTime >= 2000){
                finishFastLog();
            }
        }

        if(isContinuousLogging && continuousFos!=null){
            try{
                String line = rpm+","+map+","+adv+"\n";
                continuousFos.write(line.getBytes());
            }catch(Exception e){}
        }
    }

    private void showEditDialog(int r,int c){
        if(getContext()==null) return;
        EditText input=new EditText(getContext()); input.setText(String.valueOf(advMap[r][c])); input.setInputType(2);
        new AlertDialog.Builder(getContext()).setTitle("Edit ADV ["+MAP_GRID[r]+"kPa , "+RPM_GRID[c]+"RPM]").setView(input).setPositiveButton("SET",(d,w)->{ try{ int v=Integer.parseInt(input.getText().toString()); if(v>=0&&v<=50){ advMap[r][c]=v; if(cellViews[r][c]!=null) cellViews[r][c].setText(String.valueOf(v)); send("$SET,"+r+","+c+","+v); } }catch(Exception e){} }).setNegativeButton("Batal",null).show();
    }

    private void send(String orig){
        new Thread(()->{
            try{
                String toSend;
                if(orig.equals("$GET")) toSend = "$GET\r\n";
                else if(orig.equals("$SAVE")) toSend = "$SAVE\r\n";
                else if(orig.startsWith("$SET")) toSend = orig + "\r\n";
                else { toSend = orig; if(!toSend.endsWith("\r\n")) toSend = toSend + "\r\n"; }

                byte[] bytes = toSend.getBytes();
                status("TX: "+orig);
                if(currentPort!=null){
                    for(int i=0;i<bytes.length;i++){
                        byte[] one = new byte[]{bytes[i]};
                        currentPort.write(one, 300);
                        Thread.sleep(5);
                    }
                }
                Thread.sleep(25);
                status(orig+" DIKIRIM - TUNGGU BALASAN...");
            }catch(Exception e){ status("TX FAIL: "+e.getMessage()); }
        }).start();
    }

    private void openFilePicker(){ Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); try{ loadFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"File picker tidak tersedia",Toast.LENGTH_SHORT).show(); } }
    private void saveMapToPhone(){
        StringBuilder sb=new StringBuilder();
        sb.append("// TARUNA TUNER V7.4 - IGNITION MAP BACKUP\n");
        sb.append("// Date: "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())+"\n");
        sb.append("// Format: Copy paste langsung ke Arduino IDE\n\n");
        sb.append("const uint8_t ignitionMap[16][16] PROGMEM = {\n");
        for(int r=0;r<16;r++){
            sb.append("  {");
            for(int c=0;c<16;c++){
                sb.append(String.format(Locale.US, "%2d", advMap[r][c]));
                if(c<15) sb.append(", ");
            }
            sb.append("}");
            if(r<15) sb.append(",");
            sb.append(" // Row "+r+" = MAP "+MAP_GRID[r]+" kPa\n");
        }
        sb.append("};\n\n");
        sb.append("// RPM GRID\n");
        sb.append("const uint16_t rpmGrid[16] = {");
        for(int i=0;i<16;i++){ sb.append(RPM_GRID[i]); if(i<15) sb.append(", "); }
        sb.append("};\n");
        sb.append("// MAP GRID\n");
        sb.append("const uint8_t mapGrid[16] = {");
        for(int i=0;i<16;i++){ sb.append(MAP_GRID[i]); if(i<15) sb.append(", "); }
        sb.append("};\n\n");
        sb.append("// CSV_START\n");
        for(int r=0;r<16;r++){
            for(int c=0;c<16;c++){ sb.append(advMap[r][c]); if(c<15) sb.append(","); }
            sb.append("\n");
        }
        sb.append("// CSV_END\n");
        pendingSaveContent=sb.toString();
        String fileName="IGN_MAP_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())+".h";
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try{ saveFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"Save tidak support",Toast.LENGTH_SHORT).show(); }
    }
    private void saveMapToUri(Uri uri, String content){ try{ OutputStream os=getActivity().getContentResolver().openOutputStream(uri); os.write(content.getBytes()); os.close(); Toast.makeText(getContext(),"Saved: "+uri.getLastPathSegment()+" (Arduino format)",Toast.LENGTH_LONG).show(); }catch(Exception e){ Toast.makeText(getContext(),"Gagal: "+e.getMessage(),Toast.LENGTH_LONG).show(); } }

    private void loadMapFromUri(Uri uri){ try{ InputStream is=getActivity().getContentResolver().openInputStream(uri); BufferedReader reader=new BufferedReader(new InputStreamReader(is)); String line; int r=0; while((line=reader.readLine())!=null && r<16){ if(line.contains("CSV_START")||line.contains("CSV_END")||line.startsWith("//")||line.startsWith("const")) continue; String[] parts=line.split(","); if(parts.length>=16){ for(int c=0;c<16;c++){ try{ advMap[r][c]=Integer.parseInt(parts[c].trim()); }catch(Exception e){} } r++; } } reader.close(); buildMapTable(); Toast.makeText(getContext(),"Loaded "+r+" rows",Toast.LENGTH_SHORT).show(); }catch(Exception e){ Toast.makeText(getContext(),"Load gagal: "+e.getMessage(),Toast.LENGTH_SHORT).show(); } }

    // USB CONNECTION (ORIGNAL WORKING)
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver(){
        public void onReceive(Context context, Intent intent){
            if(ACTION_USB_PERMISSION.equals(intent.getAction())){
                synchronized(this){
                    UsbDevice device=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted=intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false);
                    if(granted && device!=null) connect(); else status("permission denied");
                    try{ if(receiverRegistered){ context.unregisterReceiver(this); receiverRegistered=false; } }catch(Exception ignored){}
                }
            }
        }
    };

    @Override public void onResume(){
        super.onResume();
        UsbManager usbManager = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
        permissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        getActivity().registerReceiver(usbPermissionReceiver, filter);
        receiverRegistered = true;
        connect();
    }

    @Override public void onPause(){
        super.onPause();
        disconnect();
        try{ if(receiverRegistered){ getActivity().unregisterReceiver(usbPermissionReceiver); receiverRegistered=false; } }catch(Exception ignored){}
    }

    private void connect(){
        UsbManager usbManager = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice device : usbManager.getDeviceList().values()){
            if(deviceId!=-1 && device.getDeviceId()!=deviceId) continue;
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if(connection==null) continue;
            UsbSerialPort port = UsbSerialProber.getDefaultProber().probeDevice(device).getPorts().get(portNum);
            try{
                port.open(connection);
                port.setParameters(115200, 8, 1, 0);
                currentPort = port;
                currentConnection = connection;
                status("CONNECTED");
                startReadThread();
                return;
            }catch(Exception e){ status("Open fail: "+e.getMessage()); }
        }
        status("No USB device");
    }

    private void disconnect(){
        stopRead = true;
        if(readThread!=null){ try{ readThread.interrupt(); }catch(Exception e){} readThread=null; }
        if(currentPort!=null){ try{ currentPort.close(); }catch(Exception e){} currentPort=null; }
        if(currentConnection!=null){ try{ currentConnection.close(); }catch(Exception e){} currentConnection=null; }
        if(isContinuousLogging) stopContinuousLog();
    }

    private void startReadThread(){
        stopRead = false;
        readThread = new Thread(()->{
            byte[] buf = new byte[256];
            while(!stopRead){
                try{
                    int len = currentPort.read(buf, 200);
                    if(len>0){
                        String data = new String(buf,0,len);
                        buffer+=data;
                        int nl;
                        while((nl=buffer.indexOf("\n"))>=0){
                            String line=buffer.substring(0,nl).trim();
                            buffer=buffer.substring(nl+1);
                            if(line.isEmpty()) continue;
                            handleLine(line);
                        }
                    }
                }catch(Exception e){ if(!stopRead) status("Read err: "+e.getMessage()); break; }
            }
        });
        readThread.start();
    }

    private void handleLine(String line){
        String clean = line.replace("*","").trim();
        if(clean.startsWith("$OK,RPM,") || (clean.contains("RPM,") && clean.contains("MAP,"))){
            try{
                String tmp = clean.replace("$OK,","").replace("$",""); String[] p=tmp.split(",");
                for(int i=0;i<p.length-1;i++){ if(p[i].trim().equals("RPM")) currentRpm=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("MAP")) currentMap=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("ADV")) currentAdv=Integer.parseInt(p[i+1].trim()); }
                boolean dfco = (currentMap<30 && currentRpm>1600);
                updateGauges(currentRpm, currentMap, currentAdv, dfco);
            }catch(Exception e){}
        } else if(clean.startsWith("$GRIDR,")){
            try{
                String[] p=clean.replace("$GRIDR,","").replace("*","").split(",");
                if(p.length>=16){ for(int i=0;i<16;i++) RPM_GRID[i]=Integer.parseInt(p[i].trim()); }
                status("GRID RPM auto dari ECU");
                if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable());
            }catch(Exception e){}
        } else if(clean.startsWith("$GRIDM,")){
            try{
                String[] p=clean.replace("$GRIDM,","").replace("*","").split(",");
                if(p.length>=16){ for(int i=0;i<16;i++) MAP_GRID[i]=Integer.parseInt(p[i].trim()); }
                status("GRID MAP auto dari ECU");
                if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable());
            }catch(Exception e){}
        } else if(clean.startsWith("$MAPR,") || clean.startsWith("MAPR,") ){
            try{ String t = clean.replace("$MAPR,","").replace("MAPR,",""); String[] p=t.split(","); int row=Integer.parseInt(p[0].trim()); for(int c=0;c<16 && c+1<p.length;c++) advMap[row][c]=Integer.parseInt(p[c+1].trim()); if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable()); status("MAP Row "+row+" diterima"); if(row==15) { Toast.makeText(getContext(),"Full MAP 16x16 diterima!",Toast.LENGTH_LONG).show(); status("FULL MAP DITERIMA 16x16"); } }catch(Exception e){ status("MAPR parse error: "+e.getMessage()); }
        } else if(clean.contains("MAPDONE")){ Toast.makeText(getContext(),"MAPDONE - Full map diterima!",Toast.LENGTH_LONG).show(); status("MAPDONE - FULL MAP DITERIMA"); if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable()); }
    }

    public void status(String str){
        if(statusText!=null && getActivity()!=null) getActivity().runOnUiThread(()->{
            if(str.toLowerCase().contains("connected")){ statusText.setText("CONNECTED"); statusText.setTextColor(Color.parseColor("#00FF88")); }
            else if(str.toLowerCase().contains("failed")||str.toLowerCase().contains("denied")||str.toLowerCase().contains("no usb")){ statusText.setText(str.toUpperCase()); statusText.setTextColor(Color.RED); }
            else statusText.setText(str.toUpperCase());
        });
    }

    // === TAP SEKALI = Continuous Log dengan dialog nama file, save di Download/tune_log/ ===
    private void showContinuousLogDialog(){
        if(getContext()==null) return;
        EditText input=new EditText(getContext());
        String defaultName = "TUNE_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        input.setText(defaultName);
        input.setHint("Nama file .txt");
        new AlertDialog.Builder(getContext())
            .setTitle("Long Log - Nama File")
            .setMessage("Log akan ditulis terus sampai klik STOP\nFolder: Download/tune_log/")
            .setView(input)
            .setPositiveButton("START LOG",(d,w)->{
                String name=input.getText().toString().trim();
                if(name.isEmpty()) name=defaultName;
                if(!name.toLowerCase().endsWith(".txt")) name+=".txt";
                startContinuousLog(name);
            })
            .setNegativeButton("Batal",null)
            .show();
    }

    private void startContinuousLog(String fileName){
        try{
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File tuneLogDir = new File(downloadDir, "tune_log");
            if(!tuneLogDir.exists()) tuneLogDir.mkdirs();
            continuousFile = new File(tuneLogDir, fileName);
            continuousFos = new FileOutputStream(continuousFile, false);
            continuousFos.write("RPM,MAP,ADV\n".getBytes());
            isContinuousLogging = true;
            continuousFileName = fileName;
            status("Long Log START: "+fileName);
            Toast.makeText(getContext(),"START LOG: "+fileName+"\nFolder tune_log",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            status("Log START fail: "+e.getMessage());
            Toast.makeText(getContext(),"Gagal START: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    private void stopContinuousLog(){
        try{
            if(continuousFos!=null){ continuousFos.flush(); continuousFos.close(); continuousFos=null; }
            isContinuousLogging = false;
            status("Long Log STOP: "+continuousFileName+" SAVED");
            Toast.makeText(getContext(),"STOP LOG SAVED:\nDownload/tune_log/"+continuousFileName,Toast.LENGTH_LONG).show();
        }catch(Exception e){
            status("Log STOP fail: "+e.getMessage());
        }
    }

    // === LONG TAP = Fast Log 2 detik sebelum + 2 detik sesudah, per file di Download/fast_tune_log/ ===
    private void doFastLog(){
        if(isContinuousLogging){
            Toast.makeText(getContext(),"Stop Long Log dulu!",Toast.LENGTH_SHORT).show();
            return;
        }
        logTriggerTime = System.currentTimeMillis();
        pendingPost.clear();
        waitingPost = true;
        status("Fast Log trigger - merekam 2 detik sesudah...");
        Toast.makeText(getContext(),"FAST LOG - tunggu 2 detik sesudah",Toast.LENGTH_SHORT).show();
    }
    
    private void finishFastLog(){
        waitingPost = false;
        logCount++;
        StringBuilder entry = new StringBuilder();
        entry.append("RPM,MAP,ADV\n");
        int skip = Math.max(0, history.size() - 20);
        int idx=0;
        for(Sample s: history){
            if(idx>=skip){
                entry.append(s.rpm).append(",").append(s.map).append(",").append(s.adv).append("\n");
            }
            idx++;
        }
        for(Sample s: pendingPost){
            entry.append(s.rpm).append(",").append(s.map).append(",").append(s.adv).append("\n");
        }
        try{
            String fileName = "FAST_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date(logTriggerTime))+".txt";
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File fastLogDir = new File(downloadDir, "fast_tune_log");
            if(!fastLogDir.exists()) fastLogDir.mkdirs();
            File file = new File(fastLogDir, fileName);
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(entry.toString().getBytes());
            fos.close();
            status("Fast Log SAVED: "+fileName);
            Toast.makeText(getContext(),"FAST LOG SAVED:\nDownload/fast_tune_log/"+fileName,Toast.LENGTH_LONG).show();
        }catch(Exception e){
            status("Fast Log fail: "+e.getMessage());
            Toast.makeText(getContext(),"Fast Log gagal: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
}
