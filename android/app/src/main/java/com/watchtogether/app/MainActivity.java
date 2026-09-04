package com.watchtogether.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.Base64;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Build;
import android.content.res.Configuration;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.TrackSelectionDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_VIDEO = 77, PICK_SUBTITLE = 78, PICK_PFP = 79;
    private static final String APP_NAME = "CoView";
    private static final int[] PFP_RES = {R.drawable.pfp01,R.drawable.pfp02,R.drawable.pfp03,R.drawable.pfp04,R.drawable.pfp05,R.drawable.pfp06,R.drawable.pfp07,R.drawable.pfp08,R.drawable.pfp09,R.drawable.pfp10,R.drawable.pfp11,R.drawable.pfp12,R.drawable.pfp13,R.drawable.pfp14,R.drawable.pfp15,R.drawable.pfp16,R.drawable.pfp17,R.drawable.pfp18,R.drawable.pfp19,R.drawable.pfp20};
    private static final String[] EMOJIS = {"😀","😂","🤣","😊","😍","🥰","😎","😭","😤","😱","🤔","🙃","❤️","🔥","✨","🎉","👏","👍","👎","😂","🍿","🎬","😺","😸","😹","😻","🙈","🙉","🙊","💜","💙","💚","💛","🖤","🤍","💯","⭐","☕","🌙","😴"};

    private int BG, SURFACE, SURFACE2, PRIMARY, PRIMARY2, TEXT, MUTED, SUCCESS, LINE;
    private boolean lightMode;
    private FrameLayout root, roomRoot, chatOverlay, roomPlayerBox;
    private LinearLayout roomContent, roomHeader, roomPeople, roomMediaRow, roomQuick;
    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private RecyclerView chatList;
    private EditText chatInput;
    private TextView participantCount, connectionLabel, mediaLabel;
    private final ArrayList<ChatMessage> chats = new ArrayList<>();
    private final LinkedHashMap<String,String> participants = new LinkedHashMap<>();
    private final HashMap<String,String> participantPfps = new HashMap<>();
    private SharedPreferences prefs;
    private String serverUrl="", anonKey="", username="", roomName="", roomPassword="", profilePicture="";
    private int pfpIndex=0;
    private int subtitleDelayMs=0;
    private TextView participantList;
    private Uri videoUri, subtitleUri;
    private boolean applyingRemote=false, chatOpen=false, fullscreen=false;
    private long remoteGuardUntil=0;
    private long lastLocalSync=0;
    private final HashMap<String,Long> lastSequenceByUser = new HashMap<>();
    private boolean initialStateWaiting = false;
    private boolean ccEnabled = true;
    private SubtitleView adjustedSubtitleView;
    private CueGroup lastCueGroup;
    private Runnable pendingSubtitleRunnable;
    private String activeStateRequestId = "";
    private String localPresenceKey = "";
    private final LinkedHashSet<String> seenEventIds = new LinkedHashSet<>();
    private final Handler uiHandler = new Handler();
    private final HashMap<String,Long> presenceNoticeTimes = new HashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!SyncService.ACTION_EVENT.equals(intent.getAction())) return;
            try {
                String ev=intent.getStringExtra(SyncService.EXTRA_EVENT), raw=intent.getStringExtra(SyncService.EXTRA_DATA);
                JSONObject d=new JSONObject(raw==null?"{}":raw);
                if ("connected".equals(ev)) {
                    localPresenceKey = d.optString("presenceKey", "");
                    if(!localPresenceKey.isEmpty()){participants.remove("self");participantPfps.remove("self");participants.put(localPresenceKey,username);participantPfps.put(localPresenceKey,profilePicture);}
                    runOnUiThread(() -> {
                        if (!isFinishing()) {
                            if (player == null) showRoom();
                            else { if (connectionLabel != null) connectionLabel.setText("●  Connected"); sendStateRequest(); }
                            refreshParticipants();
                        }
                    });
                } else if ("error".equals(ev)) {
                    runOnUiThread(() -> toast(d.optString("message","Connection failed")));
                } else if ("disconnected".equals(ev)) {
                    runOnUiThread(() -> { if(connectionLabel!=null) connectionLabel.setText("●  Reconnecting…"); });
                } else if ("sync".equals(ev)) applySync(d);
                else if ("chat".equals(ev)) { addChat(d.optString("user","Unknown"), d.optString("text",""), d.optString("pfp","")); playChatSound(); }
                else if ("presence_state".equals(ev)) updatePresenceState(d);
                else if ("presence_diff".equals(ev)) updatePresenceDiff(d);
                else if ("room_presence_join".equals(ev)) handleRoomPresenceJoin(d);
                else if ("room_presence_update".equals(ev)) handleRoomPresenceUpdate(d);
                else if ("room_presence_ack".equals(ev)) handleRoomPresenceAck(d);
                else if ("room_presence_leave".equals(ev)) handleRoomPresenceLeave(d);
                else if ("state_request".equals(ev)) sendSync(true, d.optString("requestId", ""));
            } catch(Exception ignored){}
        }
    };

    @Override protected void onCreate(Bundle state){
        prefs=getSharedPreferences("watchtogether",MODE_PRIVATE);
        subtitleDelayMs=prefs.getInt("subtitle_delay_ms",0);
        lightMode=prefs.getBoolean("light_mode",false);
        AppCompatDelegate.setDefaultNightMode(lightMode?AppCompatDelegate.MODE_NIGHT_NO:AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(state);
        applyThemeColors();
        setTitle(APP_NAME);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG); updateSystemBars();
        setContentView(R.layout.activity_main); root=findViewById(R.id.root); registerReceiverCompat(); initSounds(); ensureProfile();
        // No splash/get-started screen: go directly to the room connection information.
        showConnect();
    }

    private void registerReceiverCompat(){ IntentFilter f=new IntentFilter(SyncService.ACTION_EVENT); if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED); else registerReceiver(receiver,f); }
    private void applyThemeColors(){
        int defaultAccent=Color.rgb(20,184,166);
        PRIMARY=prefs.getInt("accent_color",defaultAccent);
        PRIMARY2=lighten(PRIMARY,0.20f);
        if(lightMode){ BG=Color.rgb(246,248,252); SURFACE=Color.WHITE; SURFACE2=Color.rgb(235,240,247); TEXT=Color.rgb(17,24,39); MUTED=Color.rgb(93,105,124); SUCCESS=Color.rgb(37,150,100); LINE=Color.rgb(211,219,231); }
        else { BG=Color.rgb(9,14,25); SURFACE=Color.rgb(18,25,40); SURFACE2=Color.rgb(27,37,57); TEXT=Color.rgb(244,248,252); MUTED=Color.rgb(151,166,188); SUCCESS=Color.rgb(75,205,137); LINE=Color.rgb(47,61,85); }
    }
    private int lighten(int color,float amount){float[] hsv=new float[3];Color.colorToHSV(color,hsv);hsv[2]=Math.min(1f,hsv[2]+amount);hsv[1]=Math.max(0.15f,hsv[1]*0.85f);return Color.HSVToColor(hsv);}
    private void updateSystemBars(){int flags=lightMode?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:0;if(lightMode&&Build.VERSION.SDK_INT>=26)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(flags);}
    private void ensureProfile(){
        pfpIndex=Math.max(0,Math.min(PFP_RES.length-1,prefs.getInt("pfp_index",0)));
        String custom=prefs.getString("custom_pfp","");
        profilePicture=custom.isEmpty()?"asset:"+pfpIndex:custom;
    }
    private Bitmap loadPfpBitmap(String token){
        try{
            if(token!=null&&token.startsWith("data:")){int comma=token.indexOf(',');if(comma>0)return BitmapFactory.decodeByteArray(Base64.decode(token.substring(comma+1),Base64.DEFAULT),0,Base64.decode(token.substring(comma+1),Base64.DEFAULT).length);}
            if(token!=null&&token.startsWith("asset:")){int i=Integer.parseInt(token.substring(6));return BitmapFactory.decodeResource(getResources(),PFP_RES[Math.max(0,Math.min(PFP_RES.length-1,i))]);}
        }catch(Exception ignored){}
        return null;
    }
    private String bitmapToData(Bitmap b){
        if(b==null)return "";
        Bitmap scaled=Bitmap.createScaledBitmap(b,96,96,true);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.PNG,88,out);if(scaled!=b)scaled.recycle();return "data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    private String displayName(Uri uri){String n=uri==null?null:uri.getLastPathSegment();if(n==null||n.trim().isEmpty())n="Selected video";int slash=n.lastIndexOf('/');if(slash>=0)n=n.substring(slash+1);return n;}
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);return t;}
    private TextView title(String s,float size){TextView t=text(s,size,TEXT);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(TEXT);e.setTextSize(15);e.setSingleLine(true);e.setPadding(dp(15),0,dp(15),0);e.setBackground(round(SURFACE2,LINE,14));return e;}
    private GradientDrawable round(int fill,int stroke,int r){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setPadding(dp(10),0,dp(10),0);b.setMinHeight(dp(52));b.setStateListAnimator(null);b.setBackground(round(primary?PRIMARY:SURFACE2,primary?PRIMARY2:LINE,15));return b;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void add(LinearLayout p,View v,int w,int h,int top,int bottom){LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(w,h);q.topMargin=dp(top);q.bottomMargin=dp(bottom);p.addView(v,q);}
    private ScrollView scroll(LinearLayout c){ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setClipToPadding(false);s.addView(c);return s;}
    private void screen(View v){root.removeAllViews();root.addView(v,new FrameLayout.LayoutParams(-1,-1));}
    private void section(LinearLayout c,String s){TextView t=text(s,11,PRIMARY2);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);add(c,t,-1,dp(28),12,5);}

    private void showConnect(){
        LinearLayout c=col();c.setPadding(dp(22),dp(18),dp(22),dp(24));
        LinearLayout top=row(); ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.ic_logo);logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);top.addView(logo,new LinearLayout.LayoutParams(dp(58),dp(58)));
        LinearLayout brand=col();TextView h=title(APP_NAME,25);add(brand,h,-1,dp(32),0,0);add(brand,text("Watch. Together.",13,MUTED),-1,dp(22),0,0);top.addView(brand,new LinearLayout.LayoutParams(0,dp(58),1));
        Button settings=button("⚙",false);top.addView(settings,new LinearLayout.LayoutParams(dp(54),dp(52)));settings.setOnClickListener(v->showSettingsDialog());add(c,top,-1,dp(58),0,10);
        LinearLayout profile=row();profile.setPadding(dp(12),dp(10),dp(12),dp(10));profile.setBackground(round(SURFACE,LINE,16));
        ImageView avatar=new ImageView(this);avatar.setImageBitmap(loadPfpBitmap(profilePicture));avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setBackground(round(SURFACE2,LINE,50));profile.addView(avatar,new LinearLayout.LayoutParams(dp(58),dp(58)));
        LinearLayout ptext=col();ptext.setPadding(dp(12),0,0,0);add(ptext,title("Your profile",15),-1,dp(25),0,0);profile.addView(ptext,new LinearLayout.LayoutParams(0,dp(58),1));Button change=button("Change",false);profile.addView(change,new LinearLayout.LayoutParams(dp(90),dp(48)));change.setOnClickListener(v->showPfpPicker());add(c,profile,-1,dp(80),0,12);
        section(c,"JOINING INFO");
        EditText s=input("Server address  •  https://xxxxx.supabase.co");s.setText(prefs.getString("server",""));add(c,s,-1,dp(54),0,8);
        EditText k=input("Supabase public key");k.setText(prefs.getString("key",""));k.setInputType(129);add(c,k,-1,dp(54),0,5);
        EditText u=input("Username");u.setText(prefs.getString("user",""));add(c,u,-1,dp(54),0,8);
        section(c,"ROOM");EditText r=input("Room name");r.setText(prefs.getString("room","Movie Night"));add(c,r,-1,dp(54),0,8);
        EditText pw=input("Room password  •  optional");add(c,pw,-1,dp(54),0,12);
        Button connect=button("Join Room",true);add(c,connect,-1,dp(56),0,0);connect.setOnClickListener(v->connect(s.getText().toString().trim(),k.getText().toString().trim(),u.getText().toString().trim(),r.getText().toString().trim(),pw.getText().toString()));
        screen(scroll(c));
    }

    private void showPfpPicker(){
        LinearLayout wrapper=col();wrapper.setPadding(dp(12),dp(8),dp(12),dp(8));
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);
        final AlertDialog[] holder=new AlertDialog[1];
        for(int r=0;r<4;r++){LinearLayout line=row();for(int c=0;c<5;c++){int index=r*5+c;ImageView a=new ImageView(this);a.setImageResource(PFP_RES[index]);a.setScaleType(ImageView.ScaleType.CENTER_CROP);a.setBackground(round(SURFACE2,LINE,50));line.addView(a,new LinearLayout.LayoutParams(0,dp(60),1));if(c<4){Space sp=new Space(this);line.addView(sp,new LinearLayout.LayoutParams(dp(7),1));}final int pick=index;a.setOnClickListener(v->{pfpIndex=pick;profilePicture="asset:"+pick;prefs.edit().putInt("pfp_index",pick).remove("custom_pfp").apply();if(!localPresenceKey.isEmpty())participantPfps.put(localPresenceKey,profilePicture);sendPresenceUpdate();if(holder[0]!=null)holder[0].dismiss();if(player==null)showConnect();else refreshParticipants();});}grid.addView(line,new LinearLayout.LayoutParams(-1,dp(60)));if(r<3)grid.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(7)));}
        Button randomButton=button("Random",true);wrapper.addView(randomButton,new LinearLayout.LayoutParams(-1,dp(48)));wrapper.addView(grid,new LinearLayout.LayoutParams(-1,-2));
        Button custom=button("Choose from device",false);wrapper.addView(custom,new LinearLayout.LayoutParams(-1,dp(48)));
        holder[0]=new AlertDialog.Builder(this).setTitle("Profile picture").setView(wrapper).setNegativeButton("Cancel",null).create();
        randomButton.setOnClickListener(v->{pfpIndex=new Random().nextInt(PFP_RES.length);profilePicture="asset:"+pfpIndex;prefs.edit().putInt("pfp_index",pfpIndex).remove("custom_pfp").apply();if(!localPresenceKey.isEmpty())participantPfps.put(localPresenceKey,profilePicture);sendPresenceUpdate();if(holder[0]!=null)holder[0].dismiss();if(player==null)showConnect();else refreshParticipants();});
        custom.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_PFP);if(holder[0]!=null)holder[0].dismiss();});
        holder[0].show();
    }

    private void showSettingsDialog(){
        LinearLayout box=col();box.setPadding(dp(20),dp(6),dp(20),dp(8));
        TextView mode=text("Appearance",13,MUTED);add(box,mode,-1,dp(25),0,5);
        LinearLayout modes=row();Button dark=button("Dark",!lightMode);Button light=button("Light",lightMode);modes.addView(dark,new LinearLayout.LayoutParams(0,dp(50),1));Space gap=new Space(this);modes.addView(gap,new LinearLayout.LayoutParams(dp(8),1));modes.addView(light,new LinearLayout.LayoutParams(0,dp(50),1));add(box,modes,-1,dp(50),0,14);
        TextView accentLabel=text("Accent colour",13,MUTED);add(box,accentLabel,-1,dp(25),0,4);
        LinearLayout accent=row();View swatch=new View(this);swatch.setBackground(round(PRIMARY,LINE,50));accent.addView(swatch,new LinearLayout.LayoutParams(dp(46),dp(46)));TextView hex=text(String.format(Locale.US,"#%06X",0xFFFFFF&(PRIMARY)),15,TEXT);accent.addView(hex,new LinearLayout.LayoutParams(0,dp(46),1));Button choose=button("Colour wheel",false);accent.addView(choose,new LinearLayout.LayoutParams(dp(130),dp(46)));add(box,accent,-1,dp(50),0,8);
        
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(APP_NAME+" settings").setView(box).setNegativeButton("Done",null).create();
        dark.setOnClickListener(v->{prefs.edit().putBoolean("light_mode",false).apply();lightMode=false;AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);dialog.dismiss();applyThemeColors();showConnect();});
        light.setOnClickListener(v->{prefs.edit().putBoolean("light_mode",true).apply();lightMode=true;AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);dialog.dismiss();applyThemeColors();showConnect();});
        choose.setOnClickListener(v->{dialog.dismiss();showColorWheel();});
        dialog.show();
    }
    private void showColorWheel(){
        ColorWheelView wheel=new ColorWheelView(this,PRIMARY);
        LinearLayout box=col();box.setPadding(dp(18),dp(8),dp(18),dp(8));box.addView(wheel,new LinearLayout.LayoutParams(-1,dp(250)));
        TextView preview=text("Accent preview",14,TEXT);preview.setGravity(Gravity.CENTER);box.addView(preview,new LinearLayout.LayoutParams(-1,dp(36)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Accent colour").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Use colour",null).create();
        wheel.setListener(color->{preview.setText(String.format(Locale.US,"#%06X",0xFFFFFF&color));preview.setTextColor(color);});
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{prefs.edit().putInt("accent_color",wheel.selectedColor).apply();applyThemeColors();dialog.dismiss();showConnect();}));dialog.show();
    }

    private void sendPresenceUpdate(){
        Intent i=new Intent(this,SyncService.class).setAction(SyncService.ACTION_PRESENCE_UPDATE).putExtra("pfp",profilePicture);
        startService(i);
    }

    private void connect(String server,String key,String user,String room,String pass){
        if(server.isEmpty()||key.isEmpty()||user.isEmpty()||room.isEmpty()){toast("Server, public key, username and room are required.");return;}
        serverUrl=server;anonKey=key;username=user;roomName=room;roomPassword=pass;ensureProfile();
        prefs.edit().putString("server",server).putString("key",key).putString("user",user).putString("room",room).apply();
        participants.clear();participantPfps.clear();chats.clear();showConnecting();
        Intent i=new Intent(this,SyncService.class).setAction(SyncService.ACTION_CONNECT).putExtra("server",server).putExtra("key",key).putExtra("user",user).putExtra("room",room).putExtra("password",pass).putExtra("pfp",profilePicture);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }
    private void showConnecting(){LinearLayout c=col();c.setGravity(Gravity.CENTER);c.setPadding(dp(28),dp(25),dp(28),dp(25));TextView h=title("Joining…",25);h.setGravity(Gravity.CENTER);add(c,h,-1,dp(55),0,4);ProgressBar p=new ProgressBar(this);add(c,p,-1,dp(65),0,8);TextView m=text("Keeping the room connection alive\n\nJoining  “"+roomName+"”",14,MUTED);m.setGravity(Gravity.CENTER);add(c,m,-1,dp(110),0,15);Button cancel=button("Cancel",false);add(c,cancel,-1,dp(52),0,0);cancel.setOnClickListener(v->disconnect());screen(c);}

    private void showRoom(){
        if(player!=null){refreshParticipants();return;}
        roomRoot=new FrameLayout(this);roomRoot.setBackgroundColor(BG);screen(roomRoot);
        roomContent=col();roomContent.setPadding(dp(14),dp(8),dp(14),dp(14));roomRoot.addView(roomContent,new FrameLayout.LayoutParams(-1,-1));
        roomHeader=row();TextView back=title("‹",34);back.setGravity(Gravity.CENTER);roomHeader.addView(back,new LinearLayout.LayoutParams(dp(42),dp(50)));TextView h=title(roomName,20);roomHeader.addView(h,new LinearLayout.LayoutParams(0,dp(50),1));participantCount=text("◉ "+Math.max(1,participants.size()),13,SUCCESS);participantCount.setGravity(Gravity.CENTER);roomHeader.addView(participantCount,new LinearLayout.LayoutParams(dp(68),dp(50)));TextView chat=text("▢",23,TEXT);chat.setGravity(Gravity.CENTER);roomHeader.addView(chat,new LinearLayout.LayoutParams(dp(44),dp(50)));add(roomContent,roomHeader,-1,dp(50),0,0);back.setOnClickListener(v->disconnect());chat.setOnClickListener(v->openChat());
        connectionLabel=text("●  Connected",11,SUCCESS);add(roomContent,connectionLabel,-1,dp(25),0,4);
        roomPeople=col();roomPeople.setPadding(dp(14),dp(10),dp(14),dp(8));roomPeople.setBackground(round(SURFACE,LINE,16));TextView ph=title("PEOPLE IN ROOM",10);ph.setTextColor(MUTED);add(roomPeople,ph,-1,dp(20),0,0);participantList=text("",13,TEXT);participantList.setLineSpacing(dp(4),1f);add(roomPeople,participantList,-1,-2,5,0);add(roomContent,roomPeople,-1,-2,0,9);
        roomPlayerBox=new FrameLayout(this);roomPlayerBox.setBackground(round(Color.BLACK,LINE,12));View pv=getLayoutInflater().inflate(R.layout.view_player,roomPlayerBox,false);roomPlayerBox.addView(pv,new FrameLayout.LayoutParams(-1,-1));playerView=(PlayerView)pv;
        adjustedSubtitleView=new SubtitleView(this);
        adjustedSubtitleView.setVisibility(ccEnabled?View.VISIBLE:View.GONE);
        adjustedSubtitleView.setClickable(false);
        adjustedSubtitleView.setFocusable(false);
        roomPlayerBox.addView(adjustedSubtitleView,new FrameLayout.LayoutParams(-1,-1));
        add(roomContent,roomPlayerBox,-1,dp(235),0,8);
        roomMediaRow=row();Button pick=button("▣  Select Video",true);roomMediaRow.addView(pick,new LinearLayout.LayoutParams(0,dp(52),1));Space gap=new Space(this);roomMediaRow.addView(gap,new LinearLayout.LayoutParams(dp(8),1));Button vlc=button("VLC",false);roomMediaRow.addView(vlc,new LinearLayout.LayoutParams(dp(76),dp(52)));add(roomContent,roomMediaRow,-1,dp(52),0,3);pick.setOnClickListener(v->pickVideo());vlc.setOnClickListener(v->toast("Integrated VLC playback can be added later; Media3 is the current player."));
        mediaLabel=text("No video selected • MKV, MP4 and common video formats supported",11,MUTED);mediaLabel.setGravity(Gravity.CENTER);add(roomContent,mediaLabel,-1,dp(30),0,4);
        roomQuick=row();Button cb=button("Chat",false);roomQuick.addView(cb,new LinearLayout.LayoutParams(0,dp(48),1));Space g=new Space(this);roomQuick.addView(g,new LinearLayout.LayoutParams(dp(8),1));Button copy=button("Copy Room",false);roomQuick.addView(copy,new LinearLayout.LayoutParams(0,dp(48),1));add(roomContent,roomQuick,-1,dp(48),0,0);cb.setOnClickListener(v->openChat());copy.setOnClickListener(v->copyRoom());
        Button leave=button("Leave room",false);leave.setBackground(null);leave.setTextColor(MUTED);leave.setTextSize(13);leave.setPadding(0,0,0,0);add(roomContent,leave,-1,dp(42),7,0);leave.setOnClickListener(v->disconnect());
        initPlayer();refreshParticipants();sendStateRequest();
    }

    private void showPlayerSettings(){
        LinearLayout box=col();box.setPadding(dp(16),dp(4),dp(16),dp(4));

        TextView playback=text("Playback",13,MUTED);add(box,playback,-1,dp(25),0,4);
        LinearLayout speeds=row();
        float[] speedValues={0.5f,0.75f,1f,1.25f,1.5f,2f};
        for(float spd:speedValues){Button b=button((spd==1f?"1×":String.format(Locale.US,"%.2g×",spd)),false);speeds.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));b.setOnClickListener(v->{if(player!=null)player.setPlaybackSpeed(spd);});if(spd!=2f){Space gap=new Space(this);speeds.addView(gap,new LinearLayout.LayoutParams(dp(5),1));}}
        add(box,speeds,-1,dp(46),0,10);

        Button audio=button("Audio track",false);add(box,audio,-1,dp(50),0,7);audio.setOnClickListener(v->{if(player==null||player.getMediaItemCount()==0){toast("Load a video first.");return;}try{new TrackSelectionDialogBuilder(this,"Audio track",player,C.TRACK_TYPE_AUDIO).setAllowAdaptiveSelections(false).setShowDisableOption(false).build().show();}catch(Exception e){toast("No selectable audio tracks available.");}});

        Button subtitle=button("Subtitle track",false);add(box,subtitle,-1,dp(50),0,7);subtitle.setOnClickListener(v->{if(player==null||player.getMediaItemCount()==0){toast("Load a video first.");return;}try{new TrackSelectionDialogBuilder(this,"Subtitle track",player,C.TRACK_TYPE_TEXT).setAllowAdaptiveSelections(false).setShowDisableOption(true).build().show();}catch(Exception e){toast("No subtitle tracks available.");}});

        Button cc=button(ccEnabled?"CC   On":"CC   Off",ccEnabled);add(box,cc,-1,dp(50),0,7);cc.setOnClickListener(v->{ccEnabled=!ccEnabled;setCaptionEnabled(ccEnabled);cc.setText(ccEnabled?"CC   On":"CC   Off");cc.setBackground(round(ccEnabled?PRIMARY:SURFACE2,ccEnabled?PRIMARY2:LINE,15));});

        TextView delayLabel=text("Subtitle delay",13,MUTED);delayLabel.setGravity(Gravity.CENTER);add(box,delayLabel,-1,dp(24),0,2);LinearLayout delayRow=row();Button minus=button("−",false);delayRow.addView(minus,new LinearLayout.LayoutParams(dp(52),dp(46)));TextView delayText=text(formatDelay(subtitleDelayMs),14,TEXT);delayText.setGravity(Gravity.CENTER);delayRow.addView(delayText,new LinearLayout.LayoutParams(0,dp(46),1));Button plus=button("+",false);delayRow.addView(plus,new LinearLayout.LayoutParams(dp(52),dp(46)));add(box,delayRow,-1,dp(46),0,7);minus.setOnClickListener(v->{subtitleDelayMs=Math.max(-10000,subtitleDelayMs-250);prefs.edit().putInt("subtitle_delay_ms",subtitleDelayMs).apply();delayText.setText(formatDelay(subtitleDelayMs));if(subtitleUri!=null)applySubtitleWithoutSync();else if(lastCueGroup!=null)applyCueGroupWithDelay(lastCueGroup);});plus.setOnClickListener(v->{subtitleDelayMs=Math.min(10000,subtitleDelayMs+250);prefs.edit().putInt("subtitle_delay_ms",subtitleDelayMs).apply();delayText.setText(formatDelay(subtitleDelayMs));if(subtitleUri!=null)applySubtitleWithoutSync();else if(lastCueGroup!=null)applyCueGroupWithDelay(lastCueGroup);});

Button upload=button("Upload custom subtitle",false);add(box,upload,-1,dp(50),0,7);upload.setOnClickListener(v->{if(videoUri==null){toast("Load a video first.");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("text/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/plain","text/vtt","application/x-subrip","text/ssa","text/x-ssa"});i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_SUBTITLE);});
        Button clear=button("Clear custom subtitle",false);add(box,clear,-1,dp(50),0,7);clear.setOnClickListener(v->{subtitleUri=null;applySubtitleWithoutSync();});
        String sub=subtitleUri==null?"No custom subtitle loaded":"Custom: "+displayName(subtitleUri);TextView status=text(sub,12,MUTED);status.setGravity(Gravity.CENTER);add(box,status,-1,dp(34),0,4);
        new AlertDialog.Builder(this).setTitle("Player settings").setView(box).setPositiveButton("Done",null).show();
    }

    private String formatDelay(int ms){return (ms>=0?"+":"")+(ms/1000.0f)+" s";}

    private void setCaptionEnabled(boolean enabled){
        ccEnabled=enabled;
        if(trackSelector!=null){
            try{trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!enabled).build());}catch(Exception ignored){}
        }
        if(adjustedSubtitleView!=null){
            adjustedSubtitleView.setVisibility(enabled?View.VISIBLE:View.GONE);
            if(enabled && lastCueGroup!=null) applyCueGroupWithDelay(lastCueGroup);
        }
    }

    private void applyCueGroupWithDelay(CueGroup group){
        lastCueGroup=group;
        if(adjustedSubtitleView==null)return;
        if(pendingSubtitleRunnable!=null)uiHandler.removeCallbacks(pendingSubtitleRunnable);
        if(!ccEnabled){adjustedSubtitleView.setVisibility(View.GONE);return;}
        adjustedSubtitleView.setVisibility(View.VISIBLE);
        long targetMs;
        if(group.presentationTimeUs!=C.TIME_UNSET){
            targetMs=(group.presentationTimeUs/1000L)+subtitleDelayMs;
        }else{
            targetMs=player==null?0:player.getCurrentPosition()+subtitleDelayMs;
        }
        long current=player==null?0:player.getCurrentPosition();
        long delay=Math.max(0L,targetMs-current);
        pendingSubtitleRunnable=()->{
            if(adjustedSubtitleView!=null && ccEnabled) adjustedSubtitleView.setCues(group.cues);
        };
        if(delay==0)pendingSubtitleRunnable.run();else uiHandler.postDelayed(pendingSubtitleRunnable,Math.min(delay,15000L));
    }

    private MediaItem buildMediaItem(){
        if(videoUri==null)return null;
        MediaItem.Builder b=new MediaItem.Builder().setUri(videoUri);
        if(subtitleUri!=null){String mime=subtitleMime(subtitleUri);if(mime!=null){
            MediaItem.SubtitleConfiguration cfg=new MediaItem.SubtitleConfiguration.Builder(subtitleUri).setMimeType(mime).setLanguage("en").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
            b.setSubtitleConfigurations(Collections.singletonList(cfg));}}
        return b.build();
    }
    private String subtitleMime(Uri uri){String n=displayName(uri).toLowerCase(Locale.ROOT);if(n.endsWith(".vtt"))return MimeTypes.TEXT_VTT;if(n.endsWith(".srt"))return MimeTypes.APPLICATION_SUBRIP;if(n.endsWith(".ass")||n.endsWith(".ssa"))return MimeTypes.TEXT_SSA;return MimeTypes.APPLICATION_SUBRIP;}
    private Uri createShiftedSubtitle(Uri source,int offsetMs){
        try{java.io.InputStream in=getContentResolver().openInputStream(source);if(in==null)return null;java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder all=new StringBuilder();String line;while((line=br.readLine())!=null){all.append(line).append('\n');}br.close();String n=displayName(source).toLowerCase(Locale.ROOT);String outText;if(n.endsWith(".ass")||n.endsWith(".ssa"))outText=shiftAss(all.toString(),offsetMs);else outText=shiftSrtVtt(all.toString(),offsetMs);java.io.File f=new java.io.File(getCacheDir(),"subtitle_shifted_"+Math.abs(offsetMs)+"_"+SystemClock.uptimeMillis()+n.substring(n.lastIndexOf('.')));java.io.FileOutputStream fos=new java.io.FileOutputStream(f);fos.write(outText.getBytes(StandardCharsets.UTF_8));fos.close();return Uri.fromFile(f);}catch(Exception e){return null;}}
    private String shiftSrtVtt(String text,int offset){java.util.regex.Pattern p=java.util.regex.Pattern.compile("(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})");java.util.regex.Matcher m=p.matcher(text);StringBuffer out=new StringBuffer();while(m.find()){String a=formatTimestamp(parseTimestamp(m.group(1))+offset);String b=formatTimestamp(parseTimestamp(m.group(2))+offset);m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(a+" --> "+b));}m.appendTail(out);return out.toString();}
    private String shiftAss(String text,int offset){java.util.regex.Pattern p=java.util.regex.Pattern.compile("(Dialogue:.*?,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,)(\\d+:\\d{2}:\\d{2}\\.\\d{2})(,.*)");java.util.regex.Matcher m=p.matcher(text);StringBuffer out=new StringBuffer();while(m.find()){String a=formatAss(parseAss(m.group(2))+offset);String b=formatAss(parseAss(m.group(4))+offset);m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(m.group(1)+a+m.group(3)+b+m.group(5)));}m.appendTail(out);return out.toString();}
    private int parseTimestamp(String s){String[] a=s.split("[:.]",-1);return Integer.parseInt(a[0])*3600000+Integer.parseInt(a[1])*60000+Integer.parseInt(a[2])*1000+Integer.parseInt(a[3]);}
    private String formatTimestamp(int ms){ms=Math.max(0,ms);int h=ms/3600000;ms%=3600000;int m=ms/60000;ms%=60000;int sec=ms/1000;int x=ms%1000;return String.format(Locale.US,"%02d:%02d:%02d.%03d",h,m,sec,x);}
    private int parseAss(String s){String[] a=s.split("[:.]",-1);return Integer.parseInt(a[0])*3600000+Integer.parseInt(a[1])*60000+Integer.parseInt(a[2])*1000+(a.length>3?Integer.parseInt(a[3])*10:0);}
    private String formatAss(int ms){ms=Math.max(0,ms);int h=ms/3600000;ms%=3600000;int m=ms/60000;ms%=60000;int sec=ms/1000;int cs=(ms%1000)/10;return String.format(Locale.US,"%d:%02d:%02d.%02d",h,m,sec,cs);}
    private void applySubtitleWithoutSync(){if(player==null||videoUri==null)return;long pos=player.getCurrentPosition();boolean playing=player.getPlayWhenReady();MediaItem item=buildMediaItem();if(item==null)return;applyingRemote=true;remoteGuardUntil=SystemClock.uptimeMillis()+1800;try{player.setMediaItem(item,pos);player.prepare();player.setPlayWhenReady(playing);}finally{applyingRemote=false;}toast(subtitleUri==null?"Custom subtitle cleared":"Custom subtitle loaded");}

    private void installPlayerUtilityControls(){
        playerView.post(()->{
            View controllerView=playerView.findViewById(androidx.media3.ui.R.id.exo_controller);
            if(!(controllerView instanceof ViewGroup)) return;
            ViewGroup controller=(ViewGroup)controllerView;
            if(controller.findViewWithTag("coview_full")!=null) return;
            TextView cc=text("CC",13,TEXT);cc.setTypeface(Typeface.DEFAULT,Typeface.BOLD);cc.setGravity(Gravity.CENTER);cc.setPadding(0,0,0,0);cc.setTag("coview_cc");cc.setBackgroundColor(Color.TRANSPARENT);cc.setAlpha(ccEnabled?1f:.55f);
            TextView full=text("□",22,TEXT);full.setGravity(Gravity.CENTER);full.setPadding(0,0,0,0);full.setTag("coview_full");full.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.BOTTOM|Gravity.END);cp.setMargins(0,0,dp(88),dp(2));
            FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(dp(42),dp(42),Gravity.BOTTOM|Gravity.END);fp.setMargins(0,0,dp(46),dp(2));
            controller.addView(cc,cp);controller.addView(full,fp);
            cc.setOnClickListener(v->{ccEnabled=!ccEnabled;setCaptionEnabled(ccEnabled);cc.setAlpha(ccEnabled?1f:.55f);});
            full.setOnClickListener(v->setFullscreen(!fullscreen));
        });
    }

    private void initPlayer(){
        trackSelector=new DefaultTrackSelector(this);
        player=new ExoPlayer.Builder(this).setTrackSelector(trackSelector).build();
        playerView.setPlayer(player);
        playerView.setUseController(true);
        View builtInSubtitles=playerView.findViewById(androidx.media3.ui.R.id.exo_subtitles);
        if(builtInSubtitles!=null) builtInSubtitles.setVisibility(View.GONE);
        installPlayerUtilityControls();
        View playerSettings=playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
        if(playerSettings!=null) playerSettings.setOnClickListener(v->showPlayerSettings());
        player.addListener(new Player.Listener(){
            @Override public void onCues(CueGroup cueGroup){
                runOnUiThread(()->applyCueGroupWithDelay(cueGroup));
            }
            @Override public void onPlayWhenReadyChanged(boolean playWhenReady,int reason){
                if(!applyingRemote && SystemClock.uptimeMillis()>=remoteGuardUntil){
                    sendSync(true);
                }
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldP,Player.PositionInfo newP,int reason){
                if(!applyingRemote && SystemClock.uptimeMillis()>=remoteGuardUntil &&
                        reason==Player.DISCONTINUITY_REASON_SEEK){
                    sendSync(false);
                }
            }
        });
    }
    private void pickVideo(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","video/x-matroska","video/mp4","video/webm","video/quicktime","video/x-msvideo","video/3gpp","application/octet-stream"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_VIDEO);
    }
    @Override protected void onActivityResult(int req,int result,@Nullable Intent data){
        super.onActivityResult(req,result,data);
        if(req==PICK_VIDEO&&result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            if(player!=null){
                applyingRemote=true;
                remoteGuardUntil=SystemClock.uptimeMillis()+1800;
                videoUri=uri;
                subtitleUri=null;
                player.setMediaItem(buildMediaItem());
                player.prepare();
                applyingRemote=false;
                String name=displayName(uri);
                mediaLabel.setText("✓  "+name+"  •  local file");
                initialStateWaiting=true;
                sendStateRequest();
            }
        } else if(req==PICK_PFP&&result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            try{java.io.InputStream in=getContentResolver().openInputStream(uri);Bitmap b=BitmapFactory.decodeStream(in);if(in!=null)in.close();String encoded=bitmapToData(b);if(!encoded.isEmpty()){profilePicture=encoded;prefs.edit().putString("custom_pfp",encoded).apply();if(!localPresenceKey.isEmpty())participantPfps.put(localPresenceKey,profilePicture);sendPresenceUpdate();if(player==null)showConnect();else refreshParticipants();}}catch(Exception ignored){}
        } else if(req==PICK_SUBTITLE&&result==Activity.RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            subtitleUri=uri;
            applySubtitleWithoutSync();
        }
    }

    private void sendSync(){ sendSync(false, ""); }
    private void sendSync(boolean force){ sendSync(force, ""); }
    private void sendSync(boolean force,String requestId){
        if(player==null || applyingRemote)return;
        if(!force && SystemClock.uptimeMillis()<remoteGuardUntil)return;
        long now=System.currentTimeMillis();
        if(!force && now-lastLocalSync<120)return;
        lastLocalSync=now;
        Intent i=new Intent(this,SyncService.class)
                .setAction(SyncService.ACTION_SYNC)
                .putExtra("position",player.getCurrentPosition())
                .putExtra("playing",player.getPlayWhenReady());
        if(requestId!=null && !requestId.isEmpty()) i.putExtra("requestId",requestId);
        startService(i);
    }
    private void sendStateRequest(){
        initialStateWaiting=true;
        activeStateRequestId=UUID.randomUUID().toString();
        Intent i=new Intent(this,SyncService.class)
                .setAction(SyncService.ACTION_STATE_REQUEST)
                .putExtra("requestId",activeStateRequestId);
        startService(i);
    }
    private void applySync(JSONObject d){
        final String user=d.optString("user","");
        final String eventId=d.optString("eventId","");
        final String requestId=d.optString("requestId","");
        final long seq=d.optLong("seq",-1);
        String senderId=d.optString("senderId","");
        if(!senderId.isEmpty() && senderId.equals(localPresenceKey))return;
        if(senderId.isEmpty() && !user.isEmpty() && user.equals(username))return;
        if(!eventId.isEmpty()){
            if(seenEventIds.contains(eventId))return;
            seenEventIds.add(eventId);
            if(seenEventIds.size()>512) seenEventIds.remove(seenEventIds.iterator().next());
        }
        if(!requestId.isEmpty() && (!initialStateWaiting || !requestId.equals(activeStateRequestId)))return;
        String sequenceKey=!senderId.isEmpty()?senderId:user;
        if(!sequenceKey.isEmpty() && seq>=0){
            Long previous=lastSequenceByUser.get(sequenceKey);
            if(previous!=null && seq<=previous)return;
            lastSequenceByUser.put(sequenceKey,seq);
        }

        long pos=d.optLong("position",0);
        boolean playing=d.optBoolean("playing",false);
        long sentAt=d.optLong("sentAt",0);
        long now=System.currentTimeMillis();
        if(playing && sentAt>0){
            long elapsed=Math.max(0,Math.min(1500,now-sentAt));
            pos+=elapsed;
        }
        final long target=Math.max(0,pos);

        runOnUiThread(()->{
            if(player==null || player.getMediaItemCount()==0)return;
            applyingRemote=true;
            remoteGuardUntil=SystemClock.uptimeMillis()+1800;
            try{
                long diff=Math.abs(player.getCurrentPosition()-target);
                if(diff>350)player.seekTo(target);
                if(player.getPlayWhenReady()!=playing)player.setPlayWhenReady(playing);
                if(!requestId.isEmpty()) {
                    initialStateWaiting=false;
                    activeStateRequestId="";
                }
            }finally{
                applyingRemote=false;
            }
        });
    }

    private void updatePresenceState(JSONObject state){
        if(state==null)return;
        if(localPresenceKey.isEmpty()){participants.put("self",username);participantPfps.put("self",profilePicture);}
        else {participants.put(localPresenceKey,username);participantPfps.put(localPresenceKey,profilePicture);}
        try{Iterator<String> it=state.keys();while(it.hasNext()){String key=it.next();JSONObject entry=state.optJSONObject(key);if(entry==null)continue;JSONArray metas=entry.optJSONArray("metas");String name=key,pfp="";if(metas!=null&&metas.length()>0){JSONObject m=metas.optJSONObject(0);if(m!=null){name=m.optString("username",key);pfp=m.optString("pfp","");}}participants.put(key,name);if(!pfp.isEmpty())participantPfps.put(key,pfp);}}catch(Exception ignored){}
        refreshParticipants();
    }
    private void updatePresenceDiff(JSONObject diff){
        try{
            JSONObject joins=diff.optJSONObject("joins");
            if(joins!=null){Iterator<String> it=joins.keys();while(it.hasNext()){String key=it.next();JSONObject entry=joins.optJSONObject(key);String name=key,pfp="";JSONArray metas=entry==null?null:entry.optJSONArray("metas");if(metas!=null&&metas.length()>0){JSONObject m=metas.optJSONObject(0);if(m!=null){name=m.optString("username",key);pfp=m.optString("pfp","");}}boolean wasPresent=participants.containsKey(key);participants.put(key,name);if(!pfp.isEmpty())participantPfps.put(key,pfp);if(!wasPresent&&shouldShowPresenceNotice(key,true))showPresenceNotice(name+" joined the room",true);}}
            JSONObject leaves=diff.optJSONObject("leaves");
            if(leaves!=null){Iterator<String> it=leaves.keys();while(it.hasNext()){String key=it.next();String name=participants.get(key);boolean existed=participants.remove(key)!=null;participantPfps.remove(key);if(name==null)name="Someone";if(existed&&shouldShowPresenceNotice(key,false))showPresenceNotice(name+" left the room",false);}}
        }catch(Exception ignored){}
        runOnUiThread(this::refreshParticipants);
    }
    private void handleRoomPresenceJoin(JSONObject d){
        String key=d.optString("key","");String name=d.optString("username","Someone");String pfp=d.optString("pfp","");
        if(key.isEmpty()||key.equals(localPresenceKey))return;
        boolean wasPresent=participants.containsKey(key);
        participants.put(key,name);if(!pfp.isEmpty())participantPfps.put(key,pfp);
        refreshParticipants();
        if(!wasPresent&&shouldShowPresenceNotice(key,true))showPresenceNotice(name+" joined the room",true);
    }
    private void handleRoomPresenceUpdate(JSONObject d){
        String key=d.optString("key","");if(key.isEmpty()||key.equals(localPresenceKey))return;
        String name=d.optString("username",participants.getOrDefault(key,"Someone"));String pfp=d.optString("pfp","");
        if(!participants.containsKey(key))participants.put(key,name);if(!pfp.isEmpty())participantPfps.put(key,pfp);refreshParticipants();
    }

    private void handleRoomPresenceAck(JSONObject d){
        String forKey=d.optString("forKey","");if(forKey.isEmpty())return;if(!localPresenceKey.isEmpty()&&!localPresenceKey.equals(forKey))return;
        String key=d.optString("key","");String name=d.optString("username","Someone");String pfp=d.optString("pfp","");
        if(key.isEmpty()||key.equals(localPresenceKey))return;
        participants.put(key,name);if(!pfp.isEmpty())participantPfps.put(key,pfp);refreshParticipants();
    }
    private void handleRoomPresenceLeave(JSONObject d){
        String key=d.optString("key","");if(key.isEmpty()||key.equals(localPresenceKey))return;
        String name=participants.get(key);if(name==null)name=d.optString("username","Someone");
        boolean existed=participants.remove(key)!=null;participantPfps.remove(key);refreshParticipants();
        if(existed&&shouldShowPresenceNotice(key,false))showPresenceNotice(name+" left the room",false);
    }

    private void refreshParticipants(){
        if(participantCount!=null)participantCount.setText("◉ "+Math.max(1,participants.size()));
        if(participantList!=null){SpannableStringBuilder b=new SpannableStringBuilder();for(Map.Entry<String,String> e:participants.entrySet()){String p=participantPfps.get(e.getKey());if(p==null||p.isEmpty())p="asset:0";appendAvatar(b,p);b.append("  ").append(e.getValue());if(e.getKey().equals(localPresenceKey)||(localPresenceKey.isEmpty()&&e.getValue().equals(username)))b.append("  (You)");b.append('\n');}participantList.setText(b,TextView.BufferType.SPANNABLE);}
    }
    private void appendAvatar(SpannableStringBuilder b,String token){Bitmap bmp=loadPfpBitmap(token);if(bmp==null)return;Bitmap small=Bitmap.createScaledBitmap(bmp,dp(30),dp(30),true);BitmapDrawable bd=new BitmapDrawable(getResources(),small);bd.setBounds(0,0,dp(30),dp(30));int start=b.length();b.append("  ");b.setSpan(new ImageSpan(bd,ImageSpan.ALIGN_BOTTOM),start,start+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    private void showPresenceNotice(String message,boolean joined){
        runOnUiThread(()->{
            if(roomRoot==null){toast(message);playPresenceSound(joined);return;}
            final TextView notice=text((joined?"＋  ":"−  ")+message,14,TEXT);notice.setGravity(Gravity.CENTER_VERTICAL);notice.setPadding(dp(16),0,dp(16),0);notice.setBackground(round(SURFACE,PRIMARY,18));
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,dp(52),Gravity.TOP|Gravity.CENTER_HORIZONTAL);p.topMargin=dp(18);roomRoot.addView(notice,p);
            notice.setElevation(dp(12));playPresenceSound(joined);notice.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            uiHandler.postDelayed(()->{if(notice.getParent()!=null)((ViewGroup)notice.getParent()).removeView(notice);},3200);
        });
    }
    private void initSounds(){
        // Notification audio is played as a short media/sonification clip rather than
        // through SoundPool's notification stream. This makes it reliable while the
        // player is fullscreen and avoids depending on a separately loaded SoundPool id.
    }
    private void playNotificationSound(int resId){
        try{
            // MediaPlayer.create() returns a prepared player. Do not change its audio
            // attributes after preparation (that can fail on some Android builds).
            // Playing on the normal media stream keeps these short sounds audible while
            // the room/player is fullscreen.
            final MediaPlayer mp=MediaPlayer.create(this,resId);
            if(mp==null)return;
            mp.setVolume(0.85f,0.85f);
            mp.setOnCompletionListener(MediaPlayer::release);
            mp.setOnErrorListener((p,what,extra)->{try{p.release();}catch(Exception ignored){}return true;});
            mp.start();
        }catch(Exception ignored){}
    }
    private void playChatSound(){playNotificationSound(R.raw.chat_message);}
    private void playPresenceSound(boolean joined){playNotificationSound(joined?R.raw.room_join:R.raw.room_leave);}
    private boolean shouldShowPresenceNotice(String key,boolean joined){
        String id=(joined?"J:":"L:")+key;long now=SystemClock.uptimeMillis();Long last=presenceNoticeTimes.get(id);if(last!=null&&now-last<5000)return false;presenceNoticeTimes.put(id,now);return true;
    }

    private void openChat(){if(chatOpen)return;chatOpen=true;chatOverlay=new FrameLayout(this);chatOverlay.setBackgroundColor(Color.argb(lightMode?225:235,lightMode?246:9,lightMode?248:14,lightMode?252:25));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-1);p.gravity=Gravity.BOTTOM;roomRoot.addView(chatOverlay,p);
        LinearLayout panel=col();panel.setPadding(dp(14),dp(8),dp(14),dp(12));panel.setBackground(round(SURFACE,LINE,20));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,dp(500),Gravity.BOTTOM);chatOverlay.addView(panel,pp);
        LinearLayout head=row();TextView back=title("‹",34);back.setGravity(Gravity.CENTER);head.addView(back,new LinearLayout.LayoutParams(dp(42),dp(50)));TextView h=title("Room Chat",20);head.addView(h,new LinearLayout.LayoutParams(0,dp(50),1));TextView close=text("×",28,TEXT);close.setGravity(Gravity.CENTER);head.addView(close,new LinearLayout.LayoutParams(dp(42),dp(50)));add(panel,head,-1,dp(50),0,5);back.setOnClickListener(v->closeChat());close.setOnClickListener(v->closeChat());
        chatList=new RecyclerView(this);chatList.setLayoutManager(new LinearLayoutManager(this));chatList.setAdapter(new ChatAdapter());panel.addView(chatList,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout in=row();Button emoji=button("☺",false);in.addView(emoji,new LinearLayout.LayoutParams(dp(52),dp(54)));Space g1=new Space(this);in.addView(g1,new LinearLayout.LayoutParams(dp(7),1));chatInput=input("Type a message…");in.addView(chatInput,new LinearLayout.LayoutParams(0,dp(54),1));Space gap=new Space(this);in.addView(gap,new LinearLayout.LayoutParams(dp(7),1));Button send=button("➤",true);in.addView(send,new LinearLayout.LayoutParams(dp(62),dp(54)));add(panel,in,-1,dp(54),8,0);emoji.setOnClickListener(v->showEmojiPicker());send.setOnClickListener(v->sendChat());chatInput.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEND){sendChat();return true;}return false;});chatInput.requestFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}
    private void showEmojiPicker(){
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);grid.setPadding(dp(10),dp(8),dp(10),dp(8));for(int r=0;r<5;r++){LinearLayout line=row();for(int c=0;c<8;c++){int idx=r*8+c;if(idx>=EMOJIS.length)break;TextView e=text(EMOJIS[idx],25,TEXT);e.setGravity(Gravity.CENTER);line.addView(e,new LinearLayout.LayoutParams(0,dp(48),1));e.setOnClickListener(v->{if(chatInput!=null){int start=chatInput.getSelectionStart();chatInput.getText().insert(Math.max(0,start),((TextView)v).getText());chatInput.requestFocus();}});}grid.addView(line,new LinearLayout.LayoutParams(-1,dp(48)));}new AlertDialog.Builder(this).setTitle("Emoji").setView(grid).setNegativeButton("Close",null).show();}
    private void closeChat(){if(!chatOpen)return;chatOpen=false;if(chatOverlay!=null){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chatOverlay.getWindowToken(),0);roomRoot.removeView(chatOverlay);chatOverlay=null;chatList=null;chatInput=null;}}
    private void sendChat(){if(chatInput==null)return;String s=chatInput.getText().toString().trim();if(s.isEmpty())return;Intent i=new Intent(this,SyncService.class).setAction(SyncService.ACTION_CHAT).putExtra("text",s).putExtra("pfp",profilePicture);startService(i);addChat(username,s,profilePicture);playChatSound();chatInput.setText("");}
    private void addChat(String user,String msg,String pfp){runOnUiThread(()->{chats.add(new ChatMessage(user,msg,pfp));if(chatList!=null){chatList.getAdapter().notifyItemInserted(chats.size()-1);chatList.scrollToPosition(chats.size()-1);}});}
    private void copyRoom(){ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText(APP_NAME+" room",roomName));toast("Room name copied: "+roomName);}

    private void setFullscreen(boolean on){
        fullscreen=on;
        if(on){setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);if(roomHeader!=null)roomHeader.setVisibility(View.GONE);if(connectionLabel!=null)connectionLabel.setVisibility(View.GONE);if(roomPeople!=null)roomPeople.setVisibility(View.GONE);if(roomMediaRow!=null)roomMediaRow.setVisibility(View.GONE);if(mediaLabel!=null)mediaLabel.setVisibility(View.GONE);if(roomQuick!=null)roomQuick.setVisibility(View.GONE);if(roomContent!=null){roomContent.setPadding(0,0,0,0);roomContent.setGravity(Gravity.CENTER);}if(roomPlayerBox!=null){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)roomPlayerBox.getLayoutParams();lp.height=-1;lp.width=-1;lp.topMargin=0;lp.bottomMargin=0;roomPlayerBox.setLayoutParams(lp);}getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
        else{setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);if(roomHeader!=null)roomHeader.setVisibility(View.VISIBLE);if(connectionLabel!=null)connectionLabel.setVisibility(View.VISIBLE);if(roomPeople!=null)roomPeople.setVisibility(View.VISIBLE);if(roomMediaRow!=null)roomMediaRow.setVisibility(View.VISIBLE);if(mediaLabel!=null)mediaLabel.setVisibility(View.VISIBLE);if(roomQuick!=null)roomQuick.setVisibility(View.VISIBLE);if(roomContent!=null){roomContent.setPadding(dp(14),dp(8),dp(14),dp(14));roomContent.setGravity(Gravity.NO_GRAVITY);}if(roomPlayerBox!=null){LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)roomPlayerBox.getLayoutParams();lp.height=dp(235);lp.width=-1;lp.topMargin=0;lp.bottomMargin=dp(8);roomPlayerBox.setLayoutParams(lp);}getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);updateSystemBars();}
    }

    private void disconnect(){closeChat();if(fullscreen)setFullscreen(false);Intent i=new Intent(this,SyncService.class).setAction(SyncService.ACTION_DISCONNECT);startService(i);if(player!=null){player.release();player=null;}playerView=null;adjustedSubtitleView=null;lastCueGroup=null;roomRoot=null;roomContent=null;showConnect();}
    @Override public void onBackPressed(){if(chatOpen){closeChat();return;}if(fullscreen){setFullscreen(false);return;}if(player!=null){disconnect();return;}super.onBackPressed();}
    @Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);if(fullscreen&&newConfig.orientation==Configuration.ORIENTATION_LANDSCAPE){/* Keep the existing player; do not recreate it. */}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){if(pendingSubtitleRunnable!=null)uiHandler.removeCallbacks(pendingSubtitleRunnable);uiHandler.removeCallbacksAndMessages(null);try{unregisterReceiver(receiver);}catch(Exception ignored){}if(player!=null){player.release();player=null;}super.onDestroy();}

    private static class ChatMessage{final String user,text,pfp;ChatMessage(String u,String t,String p){user=u;text=t;pfp=p;}}
    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder>{class Holder extends RecyclerView.ViewHolder{TextView t;Holder(View v){super(v);t=(TextView)v;}}@Override public Holder onCreateViewHolder(ViewGroup p,int type){TextView t=text("",14,TEXT);t.setPadding(dp(12),dp(10),dp(12),dp(10));return new Holder(t);}@Override public void onBindViewHolder(Holder h,int pos){ChatMessage m=chats.get(pos);SpannableStringBuilder b=new SpannableStringBuilder();appendAvatar(b,m.pfp);b.append("  ").append(m.user).append("\n").append(m.text);h.t.setText(b,TextView.BufferType.SPANNABLE);h.t.setBackground(round(SURFACE2,LINE,14));}@Override public int getItemCount(){return chats.size();}}

    private class ColorWheelView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);private int selectedColor;private OnColorChanged listener;
        ColorWheelView(Context c,int initial){super(c);selectedColor=initial;setLayerType(View.LAYER_TYPE_SOFTWARE,null);marker.setStyle(Paint.Style.STROKE);marker.setStrokeWidth(dp(3));marker.setColor(Color.WHITE);}
        void setListener(OnColorChanged l){listener=l;}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(cx,cy)-dp(14);int[] colors=new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED};float[] stops=new float[]{0f,1f/6f,2f/6f,3f/6f,4f/6f,5f/6f,1f};paint.setShader(new SweepGradient(cx,cy,colors,stops));canvas.drawCircle(cx,cy,r,paint);paint.setShader(new RadialGradient(cx,cy, r,new int[]{Color.WHITE,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));canvas.drawCircle(cx,cy,r,paint);paint.setShader(null);float[] hsv=new float[3];Color.colorToHSV(selectedColor,hsv);double angle=Math.toRadians(hsv[0]);float rr=r*hsv[1];float mx=cx+(float)Math.cos(angle)*rr,my=cy+(float)Math.sin(angle)*rr;canvas.drawCircle(mx,my,dp(8),marker);}
        @Override public boolean onTouchEvent(android.view.MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_DOWN&&e.getAction()!=MotionEvent.ACTION_MOVE)return true;float cx=getWidth()/2f,cy=getHeight()/2f;float dx=e.getX()-cx,dy=e.getY()-cy;float r=Math.min(cx,cy)-dp(14);float dist=(float)Math.sqrt(dx*dx+dy*dy);float sat=Math.min(1f,dist/r);float hue=(float)Math.toDegrees(Math.atan2(dy,dx));if(hue<0)hue+=360f;if(hue>=360)hue-=360f;selectedColor=Color.HSVToColor(new float[]{hue,sat,1f});if(listener!=null)listener.onColorChanged(selectedColor);invalidate();return true;}
        interface OnColorChanged{void onColorChanged(int color);}
    }
}
