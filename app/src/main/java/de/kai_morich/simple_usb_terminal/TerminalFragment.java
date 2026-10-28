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

    private int[] RPM_GRID = {500,700,900,1100,1300,1500,1800,2200,2600,3000,3500,4000,4500,5000,5750,6500};
    private int[] MAP_GRID = {20,25,30,35,40,45,50,55,60,65,70,75,80,85,92,100};
    private int[][] advMap = new int[16][16];
    private TextView[][] cellViews = new TextView[16][16];
    private int currentRpm = 0, currentMap = 45, currentAdv = 16;
    private int lastActiveRow=-1, lastActiveCol=-1;
    private String buffer = "";

    private static class Sample { long t; int rpm,map,adv; Sample(long t,int r,int m,int a){this.t=t;rpm=r;map=m;adv=a;} }
    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private final ArrayDeque<Sample> pendingPost = new ArrayDeque<>();
    private boolean waitingPost = false;
    private long logTriggerTime = 0;
    private String pendingNote = "";
    private int pendingRow=0, pendingCol=0;
    private int logCount = 0;
    private StringBuilder fullLog = new StringBuilder();
    private String pendingSaveContent = "";

    // === CONTINUOUS LOG V2 ===
    private boolean isContinuousLogging = false;
    private java.io.File continuousFile = null;
    private java.io.FileOutputStream continuousFos = null;
    private long continuousStartTime = 0;
    private String continuousFileName = "";
    private int continuousLogCount = 0;

    private View pageGauges, pageGrid;
    private GaugeView gaugeRpm, gaugeMap, gaugeAdv, gaugeDfco;
    private TextView dfcoLamp, liveCellInfo, statusText;
    private LinearLayout mapTableContainer;
    private Button btnBack, btnGet, btnSave, btnLoad;
    private Button btnLog, btnShareLog, btnClearLog;

    private ActivityResultLauncher<Intent> loadFileLauncher, saveFileLauncher;

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        Bundle args = getArguments();
        if(args!=null){ deviceId = args.getInt("device", -1); portNum = args.getInt("port", 0); }
        for(int r=0;r<16;r++) for(int c=0;c<16;c++) advMap[r][c]=5;
        loadFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == android.app.Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData(); if(uri!=null) loadMapFromUri(uri);
            }
        });
        saveFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode() == android.app.Activity.RESULT_OK && result.getData()!=null){
                Uri uri = result.getData().getData(); if(uri!=null) saveMapToUri(uri, pendingSaveContent);
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
        btnShareLog = view.findViewById(R.id.btnShareLog);
        btnClearLog = view.findViewById(R.id.btnClearLog);
        if(btnBack!=null) btnBack.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.VISIBLE); if(pageGrid!=null) pageGrid.setVisibility(View.GONE); });
        if(gaugeRpm!=null) gaugeRpm.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); buildMapTable(); });
        if(gaugeMap!=null) gaugeMap.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); buildMapTable(); });
        if(gaugeAdv!=null) gaugeAdv.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); buildMapTable(); });
        if(gaugeDfco!=null) gaugeDfco.setOnClickListener(v->{ if(pageGauges!=null) pageGauges.setVisibility(View.GONE); if(pageGrid!=null) pageGrid.setVisibility(View.VISIBLE); buildMapTable(); });
        if(btnGet!=null) btnGet.setOnClickListener(v-> send("$GET"));
        if(btnSave!=null) btnSave.setOnClickListener(v-> saveMapToPhone());
        if(btnLoad!=null) btnLoad.setOnClickListener(v-> openFilePicker());
        // === LOGIC BARU BLACKBOX ===
        if(btnLog!=null){
            btnLog.setOnClickListener(v-> {
                if(isContinuousLogging){
                    showStopContinuousDialog();
                } else {
                    showStartContinuousDialog();
                }
            });
            btnLog.setOnLongClickListener(v->{ doSnapshotLog(); return true; });
        }
        if(btnShareLog!=null) btnShareLog.setOnClickListener(v-> shareLog());
        if(btnClearLog!=null) btnClearLog.setOnClickListener(v->{ fullLog.setLength(0); logCount=0; Toast.makeText(getContext(),"Log cleared",Toast.LENGTH_SHORT).show(); });
        buildMapTable();
        if(pageGauges!=null) pageGauges.setVisibility(View.VISIBLE);
        if(pageGrid!=null) pageGrid.setVisibility(View.GONE);
        updateGauges(currentRpm, currentMap, currentAdv, false);
        updateLogButtonUI();
        connect();
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
                TextView cell=new TextView(getContext()); cell.setText(String.valueOf(advMap[r][c])); cell.setWidth(120); cell.setHeight(90); cell.setGravity(17); cell.setPadding(8,8,8,8);
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
            if(liveCellInfo!=null && getActivity()!=null){
                final int fRow=activeRow, fCol=activeCol, fAdv=currentAdv;
                getActivity().runOnUiThread(()->liveCellInfo.setText("Live Cell: RPM "+RPM_GRID[fCol]+" / MAP "+MAP_GRID[fRow]+" kPa -> Row "+fRow+" Col "+fCol+" = "+advMap[fRow][fCol]+" deg | Real ADV: "+fAdv+" deg"));
            }
        }catch(Exception e){}
    }

    private void updateGauges(int rpm, int map, int adv, boolean dfco){
        currentRpm=rpm; currentMap=map; currentAdv=adv;
        if(getActivity()!=null) getActivity().runOnUiThread(()->{
            try{
                if(gaugeRpm!=null) gaugeRpm.setValue(rpm, "RPM", rpm+" RPM", 0, 7000);
                if(gaugeMap!=null) gaugeMap.setValue(map, "MAP", map+" kPa", 0, 100);
                if(gaugeAdv!=null) gaugeAdv.setValue(adv, "ADV", adv+" deg", 0, 50);
                if(gaugeDfco!=null){ int dfcoVal = dfco ? 100 : 0; gaugeDfco.setValue(dfcoVal, "DFCO", dfco?"ON":"OFF", 0, 100); }
                if(dfcoLamp!=null){ dfcoLamp.setText(dfco?"DFCO ON":"DFCO OFF"); }
                updateActiveHighlight(rpm, map);
                // Jika continuous logging aktif, tulis tiap sample
                if(isContinuousLogging && continuousFos!=null){
                    try{
                        long elapsed = System.currentTimeMillis() - continuousStartTime;
                        int rIdx=0,cIdx=0;
                        for(int i=0;i<15;i++) if(map>=MAP_GRID[i] && map<MAP_GRID[i+1]){rIdx=i; break;}
                        for(int i=0;i<15;i++) if(rpm>=RPM_GRID[i] && rpm<RPM_GRID[i+1]){cIdx=i; break;}
                        if(rpm>=RPM_GRID[15]) cIdx=15;
                        if(map>=MAP_GRID[15]) rIdx=15;
                        String line = String.format(Locale.US, "%d %d %d\n", rpm, map, adv);
                        continuousFos.write(line.getBytes());
                        continuousLogCount++;
                        if(continuousLogCount%10==0){
                            status("LOG "+continuousFileName+" : "+continuousLogCount+" samples");
                        }
                    }catch(Exception e){}
                }
            }catch(Exception e){}
        });
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
                    for(int i=0;i<bytes.length;i++){ byte[] one = new byte[]{bytes[i]}; currentPort.write(one, 300); Thread.sleep(5); }
                }
                Thread.sleep(25);
                status(orig+" DIKIRIM - TUNGGU BALASAN...");
            }catch(Exception e){ status("TX FAIL: "+e.getMessage()); }
        }).start();
    }

    private void openFilePicker(){ Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); try{ loadFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"File picker tidak tersedia",Toast.LENGTH_SHORT).show(); } }

    private void loadMapFromUri(Uri uri){
        try{
            InputStream is=getActivity().getContentResolver().openInputStream(uri);
            BufferedReader reader=new BufferedReader(new InputStreamReader(is));
            String line;
            java.util.List<int[]> rows = new java.util.ArrayList<>();
            java.util.List<Integer> rpmList = new java.util.ArrayList<>();
            java.util.List<Integer> mapList = new java.util.ArrayList<>();
            boolean inMap=false;
            boolean afterMap=false;
            while((line=reader.readLine())!=null){
                String t=line.trim();
                if(t.isEmpty() || t.startsWith("//")) continue;
                if(t.contains("PROGMEM")){ inMap=true; continue; }
                if(inMap){
                    if(t.startsWith("};")){ inMap=false; afterMap=true; continue; }
                    if(t.contains("{") && t.contains("}")){
                        String inside = t.substring(t.indexOf("{")+1, t.lastIndexOf("}"));
                        String[] parts=inside.split(",");
                        if(parts.length>=16){
                            int[] row=new int[16];
                            boolean ok=true;
                            for(int c=0;c<16;c++){ try{ row[c]=Integer.parseInt(parts[c].trim()); }catch(Exception e){ ok=false; break; } }
                            if(ok) rows.add(row);
                        }
                    }
                    continue;
                }
                if(afterMap && t.startsWith("{") && t.endsWith("};")){
                    String inside = t.substring(t.indexOf("{")+1, t.lastIndexOf("}"));
                    String[] parts=inside.split(",");
                    if(rpmList.isEmpty()){
                        for(String p: parts){ try{ rpmList.add(Integer.parseInt(p.trim())); }catch(Exception e){} }
                        if(rpmList.size()==16){ for(int i=0;i<16;i++) RPM_GRID[i]=rpmList.get(i); }
                    } else if(mapList.isEmpty()){
                        for(String p: parts){ try{ mapList.add(Integer.parseInt(p.trim())); }catch(Exception e){} }
                        if(mapList.size()==16){ for(int i=0;i<16;i++) MAP_GRID[i]=mapList.get(i); }
                    }
                }
            }
            reader.close();
            if(rows.size()>=16){
                for(int i=0;i<16 && i<rows.size();i++){ for(int c=0;c<16;c++) advMap[i][c]=rows.get(i)[c]; }
                buildMapTable();
                Toast.makeText(getContext(),"Loaded MAP "+rows.size()+" rows + RPM/MAP grid",Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(),"Format tidak dikenal",Toast.LENGTH_SHORT).show();
            }
        }catch(Exception e){ Toast.makeText(getContext(),"Load gagal: "+e.getMessage(),Toast.LENGTH_SHORT).show(); }
    }

    private void saveMapToPhone(){
        StringBuilder sb=new StringBuilder();
        sb.append("PROGMEM = {\n");
        for(int r=0;r<16;r++){
            sb.append("  {");
            for(int c=0;c<16;c++){
                sb.append(String.format(Locale.US, "%2d", advMap[r][c]));
                if(c<15) sb.append(", ");
            }
            sb.append(" },\n");
        }
        sb.append("};\n\n");
        sb.append("// RPM breakpoints\n");
        sb.append(" {");
        for(int i=0;i<16;i++){ sb.append(RPM_GRID[i]); if(i<15) sb.append(", "); }
        sb.append("};\n");
        sb.append("// KPA breakpoints\n");
        sb.append(" {");
        for(int i=0;i<16;i++){ sb.append(MAP_GRID[i]); if(i<15) sb.append(", "); }
        sb.append("};\n");
        pendingSaveContent=sb.toString();
        String fileName="MAP_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())+".txt";
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try{ saveFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"Save tidak support",Toast.LENGTH_SHORT).show(); }
    }

    private void saveMapToUri(Uri uri, String content){ try{ OutputStream os=getActivity().getContentResolver().openOutputStream(uri); os.write(content.getBytes()); os.close(); Toast.makeText(getContext(),"Saved: "+uri.getLastPathSegment(),Toast.LENGTH_LONG).show(); }catch(Exception e){ Toast.makeText(getContext(),"Gagal: "+e.getMessage(),Toast.LENGTH_LONG).show(); } }

    // === NEW BLACKBOX LOGIC V2 ===
    private void showStartContinuousDialog(){
        if(getContext()==null) return;
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40,20,40,20);
        TextView label = new TextView(getContext());
        label.setText("Nama file .txt akan disimpan di Download/tune_log/");
        label.setTextColor(Color.GRAY);
        label.setTextSize(12);
        EditText input = new EditText(getContext());
        String defName = "TUNE_LOG_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())+".txt";
        input.setText(defName);
        input.setHint("contoh: log_nanjak.txt");
        layout.addView(label);
        layout.addView(input);

        new AlertDialog.Builder(getContext())
            .setTitle("START CONTINUOUS LOG")
            .setMessage("Tulis RPM,MAP,ADV setiap data masuk dari ECU sampai tombol STOP di klik.\n\nTap = START, Long Press = Snapshot 1 detik before+after")
            .setView(layout)
            .setPositiveButton("START",(d,w)->{
                String fname = input.getText().toString().trim();
                if(fname.isEmpty()) fname = defName;
                if(!fname.toLowerCase().endsWith(".txt")) fname = fname + ".txt";
                startContinuousLogging(fname);
            })
            .setNegativeButton("Batal",null)
            .show();
    }

    private void showStopContinuousDialog(){
        if(getContext()==null) return;
        new AlertDialog.Builder(getContext())
            .setTitle("STOP LOGGING?")
            .setMessage("File: "+continuousFileName+"\nSamples: "+continuousLogCount+"\n\nMau berhenti dan simpan?")
            .setPositiveButton("STOP & SIMPAN",(d,w)-> stopContinuousLogging())
            .setNegativeButton("Lanjutkan LOG",null)
            .setNeutralButton("Batal + Hapus",(d,w)-> cancelContinuousLogging())
            .show();
    }

    private void startContinuousLogging(String fileName){
        try{
            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File tuneLogDir = new java.io.File(downloadDir, "tune_log");
            if(!tuneLogDir.exists()) tuneLogDir.mkdirs();
            // Bersihkan nama file dari karakter illegal
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            continuousFile = new java.io.File(tuneLogDir, fileName);
            continuousFos = new java.io.FileOutputStream(continuousFile);
            continuousFileName = fileName;
            continuousStartTime = System.currentTimeMillis();
            continuousLogCount = 0;
            isContinuousLogging = true;
            // Tulis header
            String header = String.format(Locale.US, "# rpm kpa advance\n");
            continuousFos.write(header.getBytes());
            updateLogButtonUI();
            status("LOG START: "+fileName);
            Toast.makeText(getContext(),"START LOG: "+fileName+" di Download/tune_log/", Toast.LENGTH_LONG).show();
        }catch(Exception e){
            Toast.makeText(getContext(),"Gagal start log: "+e.getMessage(), Toast.LENGTH_LONG).show();
            isContinuousLogging = false;
        }
    }

    private void stopContinuousLogging(){
        try{
            if(continuousFos!=null){
                continuousFos.flush();
                continuousFos.close();
            }
            isContinuousLogging = false;
            String msg = "LOG STOPPED: "+continuousFileName+" ("+continuousLogCount+" samples) -> Download/tune_log/";
            status(msg);
            Toast.makeText(getContext(),msg, Toast.LENGTH_LONG).show();
            updateLogButtonUI();
            continuousFos = null;
            continuousFile = null;
        }catch(Exception e){
            Toast.makeText(getContext(),"Gagal stop: "+e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelContinuousLogging(){
        try{
            if(continuousFos!=null){ continuousFos.close(); }
            if(continuousFile!=null && continuousFile.exists()) continuousFile.delete();
            isContinuousLogging = false;
            status("LOG Dibatalkan & Dihapus");
            Toast.makeText(getContext(),"LOG Dibatalkan", Toast.LENGTH_SHORT).show();
            updateLogButtonUI();
        }catch(Exception e){}
    }

    private void updateLogButtonUI(){
        if(getActivity()==null) return;
        getActivity().runOnUiThread(()->{
            if(btnLog!=null){
                if(isContinuousLogging){
                    btnLog.setText("STOP LOG ("+continuousLogCount+")");
                    btnLog.setBackgroundColor(Color.parseColor("#FF4444"));
                } else {
                    btnLog.setText("LOG BLACKBOX");
                    btnLog.setBackgroundColor(Color.parseColor("#FF9800"));
                }
            }
        });
    }

    // LONG PRESS = SNAPSHOT 1 DETIK BEFORE+AFTER ke folder sama tune_log
    private void doSnapshotLog(){
        if(getContext()==null) return;
        long now = System.currentTimeMillis();
        int rIdx=0,cIdx=0;
        for(int i=0;i<15;i++) if(currentMap>=MAP_GRID[i] && currentMap<MAP_GRID[i+1]){rIdx=i; break;}
        for(int i=0;i<15;i++) if(currentRpm>=RPM_GRID[i] && currentRpm<RPM_GRID[i+1]){cIdx=i; break;}
        if(currentRpm>=RPM_GRID[15]) cIdx=15;
        if(currentMap>=MAP_GRID[15]) rIdx=15;
        final int fr=rIdx, fc=cIdx;
        logTriggerTime = now;
        pendingRow = fr; pendingCol = fc;
        pendingNote = "SNAPSHOT";
        pendingPost.clear();
        waitingPost = true;
        status("SNAPSHOT trigger @ "+currentRpm+" RPM - rekam 2 detik sesudah...");
        Toast.makeText(getContext(),"SNAPSHOT - tunggu 2 detik sesudah", Toast.LENGTH_SHORT).show();
    }

    private void startLogWithPrePost(String note, int r, int c){
        pendingNote = note; pendingRow=r; pendingCol=c;
        logTriggerTime = System.currentTimeMillis();
        pendingPost.clear();
        waitingPost = true;
        status("LOG trigger @ "+currentRpm+" RPM - merekam 2 detik sesudah...");
        Toast.makeText(getContext(),"LOG trigger - tunggu 2 detik sesudah",Toast.LENGTH_SHORT).show();
    }
    private void finishLogWithPost(){
        waitingPost = false;
        logCount++;
        // Kumpulkan 2 detik sebelum + 2 detik sesudah - format rpm kpa advance only
        long cutoffBefore = logTriggerTime - 2000;
        StringBuilder entry = new StringBuilder();
        // Format map kpa advance = rpm kpa advance
        for(Sample s: history){
            if(s.t >= cutoffBefore && s.t <= logTriggerTime){
                entry.append(String.format(Locale.US, "%d %d %d\n", s.rpm, s.map, s.adv));
            }
        }
        // Trigger point
        entry.append(String.format(Locale.US, "%d %d %d\n", currentRpm, currentMap, currentAdv));
        for(Sample s: pendingPost){
            entry.append(String.format(Locale.US, "%d %d %d\n", s.rpm, s.map, s.adv));
        }
        fullLog.append(entry.toString());
        // Save langsung ke Download/fast_tuneLog/log.txt - format rpm kpa advance
        saveFastTuneLog(entry.toString());
        status("SNAPSHOT #"+logCount+" SAVED to fast_tuneLog/log.txt");
        Toast.makeText(getContext(),"SNAPSHOT #"+logCount+" saved to Download/fast_tuneLog/log.txt",Toast.LENGTH_LONG).show();
    }

    private void saveFastTuneLog(String content){
        try{
            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File fastDir = new java.io.File(downloadDir, "fast_tuneLog");
            if(!fastDir.exists()) fastDir.mkdirs();
            java.io.File file = new java.io.File(fastDir, "log.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, false);
            fos.write(content.getBytes());
            fos.close();
        }catch(Exception e){
            status("Fast log save fail: "+e.getMessage());
        }
    }

    private void saveSnapshotToTuneLog(String content){
        try{
            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File tuneLogDir = new java.io.File(downloadDir, "tune_log");
            if(!tuneLogDir.exists()) tuneLogDir.mkdirs();
            String fileName = "SNAPSHOT_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date(logTriggerTime))+".txt";
            java.io.File file = new java.io.File(tuneLogDir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            fos.write(content.getBytes());
            fos.close();
            java.io.File allFile = new java.io.File(tuneLogDir, "BLACKBOX_ALL.txt");
            java.io.FileOutputStream fos2 = new java.io.FileOutputStream(allFile, true);
            fos2.write(content.getBytes());
            fos2.close();
        }catch(Exception e){
            saveLogToDownload(content);
        }
    }

    private void saveLogToDownload(String content){
        try{
            String fileName = "BLACKBOX_LOG_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date(logTriggerTime))+".txt";
            java.io.File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if(!downloadDir.exists()) downloadDir.mkdirs();
            java.io.File file = new java.io.File(downloadDir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file, true);
            fos.write(content.getBytes());
            fos.close();
        }catch(Exception e){
            try{
                String fileName = "BLACKBOX_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date(logTriggerTime))+".txt";
                java.io.File file = new java.io.File(getActivity().getExternalFilesDir(null), fileName);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                fos.write(content.getBytes());
                fos.close();
            }catch(Exception ex){ status("Log save fail: "+ex.getMessage()); }
        }
    }
    private void shareLog(){
        if(fullLog.length()==0){ Toast.makeText(getContext(),"Belum ada log",Toast.LENGTH_SHORT).show(); return; }
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, fullLog.toString());
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, "Share Blackbox Log"));
    }

    public void status(String str){
        if(statusText!=null && getActivity()!=null) getActivity().runOnUiThread(()->{
            if(str.toLowerCase().contains("connected")){ statusText.setText("CONNECTED"); statusText.setTextColor(Color.parseColor("#00FF88")); }
            else if(str.toLowerCase().contains("failed")||str.toLowerCase().contains("denied")||str.toLowerCase().contains("no usb")){ statusText.setText(str.toUpperCase()); statusText.setTextColor(Color.RED); }
            else statusText.setText(str.toUpperCase());
        });
    }

    private void connect(){
        try{
            status("connecting... DIRECT");
            UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
            UsbDevice device=null;
            for(UsbDevice v: usbManager.getDeviceList().values()) if(v.getDeviceId()==deviceId) { device=v; break; }
            if(device==null) for(UsbDevice v: usbManager.getDeviceList().values()) if(UsbSerialProber.getDefaultProber().probeDevice(v)!=null) { device=v; deviceId=v.getDeviceId(); break; }
            if(device==null){ status("no USB devices found - colok OTG"); return; }
            if(!usbManager.hasPermission(device)){
                Intent permIntent = new Intent(ACTION_USB_PERMISSION);
                permIntent.setPackage(getActivity().getPackageName());
                int flags = PendingIntent.FLAG_MUTABLE;
                permissionIntent = PendingIntent.getBroadcast(getActivity(),0,permIntent,flags);
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                try{ getActivity().registerReceiver(usbPermissionReceiver,filter,Context.RECEIVER_NOT_EXPORTED); receiverRegistered=true; }catch(Exception e){ getActivity().registerReceiver(usbPermissionReceiver,filter); receiverRegistered=true; }
                usbManager.requestPermission(device,permissionIntent);
                status("requesting permission - klik OK di popup");
                return;
            }
            UsbDeviceConnection usbConnection = usbManager.openDevice(device);
            if(usbConnection==null){ status("open failed - permission denied / busy"); return; }
            UsbSerialPort usbSerialPort=null;
            try{ usbSerialPort=UsbSerialProber.getDefaultProber().probeDevice(device).getPorts().get(portNum); }catch(Exception e){ usbSerialPort=UsbSerialProber.getDefaultProber().probeDevice(device).getPorts().get(0); }
            if(usbSerialPort==null){ status("no driver for device"); return; }
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
            try{ usbSerialPort.setDTR(false); }catch(Exception ignored){}
            try{ usbSerialPort.setRTS(false); }catch(Exception ignored){}
            currentPort=usbSerialPort; currentConnection=usbConnection;
            status("CONNECTED CDC "+device.getVendorId()+":"+device.getProductId());
            stopRead=false;
            readThread = new Thread(() -> {
                byte[] buf = new byte[4096];
                while(!stopRead){
                    try{
                        int len = currentPort.read(buf, 500);
                        if(len>0){
                            byte[] data = Arrays.copyOf(buf, len);
                            String chunk = new String(data);
                            buffer += chunk;
                            while(buffer.contains("*")){
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
                    }catch(Exception e){ if(!stopRead && getActivity()!=null) getActivity().runOnUiThread(()->status("read error: "+e.getMessage())); break; }
                }
            });
            readThread.start();
        }catch(Exception e){ status("connection failed: "+e.getMessage()); e.printStackTrace(); }
    }

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

    private void handleLine(String line){
        String clean = line.replace("*","").trim();
        if(clean.startsWith("$OK,RPM,") || (clean.contains("RPM,") && clean.contains("MAP,"))){
            try{
                String tmp = clean.replace("$OK,","").replace("$",""); String[] p=tmp.split(",");
                for(int i=0;i<p.length-1;i++){ if(p[i].trim().equals("RPM")) currentRpm=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("MAP")) currentMap=Integer.parseInt(p[i+1].trim()); if(p[i].trim().equals("ADV")) currentAdv=Integer.parseInt(p[i+1].trim()); }
                boolean dfco = (currentMap<30 && currentRpm>1600);
                updateGauges(currentRpm, currentMap, currentAdv, dfco);
                long nowMs = System.currentTimeMillis();
                history.addLast(new Sample(nowMs, currentRpm, currentMap, currentAdv));
                while(history.size()>100) history.removeFirst();
                if(waitingPost){
                    pendingPost.addLast(new Sample(nowMs, currentRpm, currentMap, currentAdv));
                    if(nowMs - logTriggerTime >= 2000){ finishLogWithPost(); }
                }
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
        } else if(clean.contains("GRIDDONE")){
            status("GRID DONE - Auto adjust selesai");
            if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable());
        } else if(clean.startsWith("$MAPR,") || clean.startsWith("MAPR,") ){
            try{ String t = clean.replace("$MAPR,","").replace("MAPR,",""); String[] p=t.split(","); int row=Integer.parseInt(p[0].trim()); for(int c=0;c<16 && c+1<p.length;c++) advMap[row][c]=Integer.parseInt(p[c+1].trim()); if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable()); status("MAP Row "+row+" diterima"); if(row==15) { Toast.makeText(getContext(),"Full MAP 16x16 diterima!",Toast.LENGTH_LONG).show(); status("FULL MAP DITERIMA 16x16"); } }catch(Exception e){ status("MAPR parse error: "+e.getMessage()); }
        } else if(clean.contains("MAPDONE")){ Toast.makeText(getContext(),"MAPDONE - Full map diterima!",Toast.LENGTH_LONG).show(); status("MAPDONE - FULL MAP DITERIMA"); if(getActivity()!=null) getActivity().runOnUiThread(()->buildMapTable()); }
        else if(clean.contains("EEPROM")){ Toast.makeText(getContext(),"Cell saved to EEPROM!",Toast.LENGTH_SHORT).show(); status("EEPROM SAVED"); }
        else if(clean.contains("SAVE_DONE")||clean.contains("$OK,SAVE")||clean.toUpperCase().contains("SAVE OK")){ Toast.makeText(getContext(),"Map Saved to EEPROM!",Toast.LENGTH_LONG).show(); status("SAVE DONE"); }
    }

    @Override public void onDestroy(){ super.onDestroy(); try{ stopRead=true; if(readThread!=null) readThread.interrupt(); }catch(Exception e){} try{ if(currentPort!=null) currentPort.close(); }catch(Exception e){} try{ if(currentConnection!=null) currentConnection.close(); }catch(Exception e){} try{ if(receiverRegistered) getActivity().unregisterReceiver(usbPermissionReceiver); }catch(Exception e){} try{ if(continuousFos!=null){ continuousFos.close(); } }catch(Exception e){} }
}
