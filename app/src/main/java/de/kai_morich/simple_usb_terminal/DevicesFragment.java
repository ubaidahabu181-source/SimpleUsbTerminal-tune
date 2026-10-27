package de.kai_morich.simple_usb_terminal;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.ListFragment;
import java.util.ArrayList;

public class DevicesFragment extends ListFragment {

    private static final String ACTION_USB_PERMISSION = "de.kai_morich.simple_usb_terminal.USB_PERMISSION";
    private final ArrayList<UsbDevice> devices = new ArrayList<>();
    private ArrayAdapter<UsbDevice> adapter;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if(granted) refresh();
            }
            refresh();
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); }

    @Override public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        adapter = new ArrayAdapter<UsbDevice>(getActivity(), android.R.layout.simple_list_item_1, devices){
            @NonNull @Override public android.view.View getView(int position, android.view.View convertView, @NonNull android.view.ViewGroup parent){
                android.view.View v = super.getView(position, convertView, parent);
                UsbDevice d = devices.get(position);
                android.widget.TextView tv = v.findViewById(android.R.id.text1);
                tv.setText(d.getDeviceName()+" Vendor "+String.format("%04X", d.getVendorId())+" Product "+String.format("%04X", d.getProductId()));
                return v;
            }
        };
        setListAdapter(adapter);
    }

    @Override public void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getActivity().registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getActivity().registerReceiver(receiver, f);
        }
        refresh();
    }

    @Override public void onPause() { super.onPause(); getActivity().unregisterReceiver(receiver); }

    void refresh(){
        try{
            UsbManager um = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
            devices.clear();
            if(um!=null) devices.addAll(um.getDeviceList().values());
            if(adapter!=null) adapter.notifyDataSetChanged();
        }catch(Exception e){}
    }

    @Override public void onListItemClick(@NonNull android.widget.ListView l, @NonNull android.view.View v, int position, long id){
        try{
            UsbDevice device = devices.get(position);
            UsbManager um = (UsbManager)getActivity().getSystemService(Context.USB_SERVICE);
            if(!um.hasPermission(device)){
                Intent piIntent = new Intent(ACTION_USB_PERMISSION);
                PendingIntent pi;
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
                    pi = PendingIntent.getBroadcast(getActivity(), 0, piIntent, PendingIntent.FLAG_MUTABLE);
                }else{
                    pi = PendingIntent.getBroadcast(getActivity(), 0, piIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                }
                um.requestPermission(device, pi);
                Toast.makeText(getActivity(), "Requesting permission - check popup", Toast.LENGTH_LONG).show();
                return;
            }
            Bundle args = new Bundle();
            args.putString("device", device.getDeviceName());
            args.putInt("port", 0);
            args.putInt("baud", 115200);
            TerminalFragment frag = new TerminalFragment();
            frag.setArguments(args);
            getParentFragmentManager().beginTransaction().replace(R.id.fragment, frag, "terminal").addToBackStack(null).commit();
        }catch(Exception e){
            Toast.makeText(getActivity(), "Click error: "+e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
