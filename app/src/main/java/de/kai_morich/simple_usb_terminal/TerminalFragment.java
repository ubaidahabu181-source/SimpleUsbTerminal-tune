package de.kai_morich.simple_usb_terminal;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
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
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

public class TerminalFragment extends Fragment {

    private View pageGauges, pageGrid;
    private GaugeView gaugeRpm, gaugeMap, gaugeAdv;
    private TextView dfcoLamp, liveCellInfo, statusText;
    private LinearLayout mapTableContainer;
    private Button btnBack, btnGet, btnSave, btnLoad;

    private int[][] advMap = new int[16][16];
    private TextView[][] cellViews = new TextView[16][16];
    private int currentRpm=800, currentMap=20, currentAdv=18;
    private int lastActiveRow=-1, lastActiveCol=-1;
    private int[] RPM_GRID={500,800,1000,1200,1500,1800,2100,2400,2700,3000,3500,4000,4500,5000,6000,7000};
    private int[] MAP_GRID={20,25,30,35,40,45,50,55,60,65,70,75,80,85,92,100};
    private String buffer="";
    private String pendingSaveContent="";
    private androidx.activity.result.ActivityResultLauncher<android.content.Intent> saveFileLauncher, loadFileLauncher;

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private boolean running = false;

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        if(getArguments()!=null){
            String devName = getArguments().getString("device");
            usbManager = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
            for(UsbDevice d : usbManager.getDeviceList().values()){
                if(d.getDeviceName().equals(devName)){ device = d; break; }
            }
            if(device==null && !usbManager.getDeviceList().isEmpty()){
                device = usbManager.getDeviceList().values().iterator().next();
            }
        }
        saveFileLauncher=registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result->{ if(result.getResultCode()==android.app.Activity.RESULT_OK && result.getData()!=null){ Uri uri=result.getData().getData(); if(uri!=null) saveMapToUri(uri, pendingSaveContent); } });
        loadFileLauncher=registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result->{ if(result.getResultCode()==android.app.Activity.RESULT_OK && result.getData()!=null){ Uri uri=result.getData().getData(); if(uri!=null) loadMapFromUri(uri); } });
        int[][] base={{16,18,18,18,19,19,20,21,22,23,24,25,26,27,28,29},{16,15,16,18,19,20,22,23,24,25,26,27,28,29,30,31},{15,15,17,18,20,21,23,24,25,26,27,28,29,30,31,32},{15,15,17,19,21,23,24,25,26,27,28,29,30,31,32,33},{16,16,18,20,21,23,25,26,27,28,29,30,31,32,33,34},{17,17,19,20,22,24,25,26,27,28,29,30,31,32,33,34},{16,16,18,19,21,23,24,25,26,27,28,29,30,31,32,33},{15,15,17,18,20,22,23,24,25,26,27,28,29,30,31,32},{15,14,16,17,19,20,22,23,24,25,26,27,28,29,30,31},{14,14,15,17,18,20,21,22,23,24,25,26,27,28,29,30},{13,13,14,16,17,18,20,21,22,23,24,25,26,27,28,29},{12,12,13,15,16,17,19,20,21,22,23,24,25,26,27,28},{12,11,12,13,15,16,17,18,19,20,21,22,23,24,25,26},{11,11,12,13,14,15,17,18,19,20,21,22,23,24,25,26},{10,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24},{8,9,10,11,12,13,15,16,17,18,19,20,21,22,23,24}};
        advMap=base;
    }

    @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
        View view=inflater.inflate(R.layout.fragment_terminal, container, false);
        pageGauges=view.findViewById(R.id.pageGauges); pageGrid=view.findViewById(R.id.pageGrid);
        gaugeRpm=view.findViewById(R.id.gaugeRpm); gaugeMap=view.findViewById(R.id.gaugeMap); gaugeAdv=view.findViewById(R.id.gaugeAdv);
        dfcoLamp=view.findViewById(R.id.dfcoLamp); mapTableContainer=view.findViewById(R.id.mapTableContainer);
        liveCellInfo=view.findViewById(R.id.liveCellInfo); statusText=view.findViewById(R.id.statusText);
        btnBack=view.findViewById(R.id.btnBack); btnGet=view.findViewById(R.id.btnGet); btnSave=view.findViewById(R.id.btnSave); btnLoad=view.findViewById(R.id.btnLoad);
        if(btnBack!=null) btnBack.setOnClickListener(v->{ pageGauges.setVisibility(View.VISIBLE); pageGrid.setVisibility(View.GONE); });
        if(gaugeAdv!=null) gaugeAdv.setOnClickListener(v->{ pageGauges.setVisibility(View.GONE); pageGrid.setVisibility(View.VISIBLE); });
        if(gaugeRpm!=null) gaugeRpm.setOnClickListener(v->{ pageGauges.setVisibility(View.GONE); pageGrid.setVisibility(View.VISIBLE); });
        if(gaugeMap!=null) gaugeMap.setOnClickListener(v->{ pageGauges.setVisibility(View.GONE); pageGrid.setVisibility(View.VISIBLE); });
        if(btnGet!=null) btnGet.setOnClickListener(v-> send("$GET\n"));
        if(btnSave!=null) btnSave.setOnClickListener(v-> saveMapToPhone());
        if(btnLoad!=null) btnLoad.setOnClickListener(v-> openFilePicker());
        buildMapTable();
        if(pageGauges!=null) pageGauges.setVisibility(View.VISIBLE);
        if(pageGrid!=null) pageGrid.setVisibility(View.GONE);
        updateGauges(currentRpm, currentMap, currentAdv, false);
        return view;
    }

    @Override public void onResume(){
        super.onResume();
        connectDirect();
    }
    @Override public void onPause(){
        super.onPause();
        disconnectDirect();
    }

    private void connectDirect(){
        if(device==null){ status("No device"); return; }
        if(usbManager==null) usbManager = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
        if(!usbManager.hasPermission(device)){ status("No permission - go back and ALLOW"); return; }
        try{
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if(driver==null){ status("No driver for "+device.getDeviceName()+" - trying CDC"); return; }
            port = driver.getPorts().get(0);
            connection = usbManager.openDevice(device);
            if(connection==null){ status("Failed open connection"); return; }
            port.open(connection);
            port.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE);
            status("CONNECTED - "+driver.getClass().getSimpleName()+" "+device.getDeviceName());
            running = true;
            startReadThread();
            send("$GET\n");
        }catch(Exception e){
            status("Connect failed: "+e.getMessage());
            android.util.Log.e("TARUNA", "connect", e);
        }
    }
    private void disconnectDirect(){
        running = false;
        try{ if(port!=null) port.close(); }catch(Exception e){}
        try{ if(connection!=null) connection.close(); }catch(Exception e){}
        port = null; connection = null;
    }

    private void startReadThread(){
        Executors.newSingleThreadExecutor().execute(()->{
            byte[] buf = new byte[256];
            while(running && port!=null){
                try{
                    int len = port.read(buf, 200);
                    if(len>0){
                        byte[] data = new byte[len];
                        System.arraycopy(buf,0,data,0,len);
                        receive(data);
                    }
                }catch(Exception e){
                    status("Read error: "+e.getMessage());
                    break;
                }
            }
        });
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
                if(dfcoLamp!=null){
                    dfcoLamp.setText(dfco?"DFCO ON":"DFCO OFF");
                    dfcoLamp.setTextColor(dfco?Color.parseColor("#00FFFF"):Color.parseColor("#555555"));
                }
                updateActiveHighlight(rpm, map);
            }catch(Exception e){}
        });
    }

    private void showEditDialog(int r,int c){
        if(getContext()==null) return;
        EditText input=new EditText(getContext()); input.setText(String.valueOf(advMap[r][c])); input.setInputType(2);
        new AlertDialog.Builder(getContext()).setTitle("Edit ADV ["+MAP_GRID[r]+"kPa , "+RPM_GRID[c]+"RPM]").setView(input).setPositiveButton("SET",(d,w)->{ try{ int v=Integer.parseInt(input.getText().toString()); if(v>=0&&v<=50){ advMap[r][c]=v; if(cellViews[r][c]!=null) cellViews[r][c].setText(String.valueOf(v)); send("$SET,"+r+","+c+","+v+"\n"); } }catch(Exception e){} }).setNegativeButton("Batal",null).show();
    }

    private void send(String str){
        try{
            if(port!=null) port.write(str.getBytes(), 200);
            status("TX: "+str.trim());
        }catch(Exception e){ status("TX Error: "+e.getMessage()); }
    }

    private void openFilePicker(){ android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(android.content.Intent.CATEGORY_OPENABLE); intent.setType("*/*"); try{ loadFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"File picker tidak tersedia",Toast.LENGTH_SHORT).show(); } }
    private void loadMapFromUri(Uri uri){ try{ InputStream is=getActivity().getContentResolver().openInputStream(uri); BufferedReader reader=new BufferedReader(new InputStreamReader(is)); String line; int r=0; while((line=reader.readLine())!=null && r<16){ String[] parts=line.split(","); if(parts.length>=16){ for(int c=0;c<16;c++){ try{ advMap[r][c]=Integer.parseInt(parts[c].trim()); }catch(Exception e){} } r++; } } reader.close(); buildMapTable(); Toast.makeText(getContext(),"Loaded "+r+" rows",Toast.LENGTH_SHORT).show(); }catch(Exception e){ Toast.makeText(getContext(),"Load gagal: "+e.getMessage(),Toast.LENGTH_SHORT).show(); } }
    private void saveMapToPhone(){ StringBuilder sb=new StringBuilder(); for(int r=0;r<16;r++){ for(int c=0;c<16;c++){ sb.append(advMap[r][c]); if(c<15) sb.append(","); } sb.append("\n"); } pendingSaveContent=sb.toString(); String fileName="ADV_MAP_"+new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())+".txt"; android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(android.content.Intent.CATEGORY_OPENABLE); intent.setType("text/plain"); intent.putExtra(android.content.Intent.EXTRA_TITLE, fileName); try{ saveFileLauncher.launch(intent); }catch(Exception e){ Toast.makeText(getContext(),"Save tidak support",Toast.LENGTH_SHORT).show(); } }
    private void saveMapToUri(Uri uri, String content){ try{ OutputStream os=getActivity().getContentResolver().openOutputStream(uri); os.write(content.getBytes()); os.close(); Toast.makeText(getContext(),"Saved: "+uri.getLastPathSegment(),Toast.LENGTH_LONG).show(); }catch(Exception e){ Toast.makeText(getContext(),"Gagal: "+e.getMessage(),Toast.LENGTH_LONG).show(); } }

    public void status(String s){ if(statusText!=null && getActivity()!=null) getActivity().runOnUiThread(()->statusText.setText(s)); }

    private void receive(byte[] data){
        buffer+=new String(data);
        int nl;
        while((nl=buffer.indexOf("\n"))>=0){
            String line=buffer.substring(0,nl).trim();
            buffer=buffer.substring(nl+1);
            if(line.isEmpty()) continue;
            String clean=line.replace("*","").trim();
            status(clean);
            int rpm=currentRpm, map=currentMap, adv=currentAdv;
            boolean found=false;
            if(clean.contains("RPM")){
                try{
                    String[] p=clean.replace("$","").split(",");
                    for(int i=0;i<p.length-1;i++){
                        String k=p[i].trim().toUpperCase();
                        if(k.equals("RPM")){ rpm=Integer.parseInt(p[i+1].replaceAll("[^0-9]","")); found=true; }
                        if(k.equals("MAP")){ map=Integer.parseInt(p[i+1].replaceAll("[^0-9]","")); found=true; }
                        if(k.equals("ADV")){ adv=Integer.parseInt(p[i+1].replaceAll("[^0-9]","")); found=true; }
                    }
                }catch(Exception e){}
            }
            if(found){ boolean dfco=(map<30 && rpm>1600); updateGauges(rpm, map, adv, dfco); }
        }
    }
}
