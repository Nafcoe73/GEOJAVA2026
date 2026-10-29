package org.rota.mapaoffline;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Html;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mapa Offline — 100% offline: lê mapa.png + rota.txt (+ ruas.bin) e usa só o GPS.
 * Destino = bolinha VERDE; eu = bolinha VERMELHA; caixa de navegação em baixo.
 */
public class MainActivity extends Activity {
    private static final int REQ_LOC = 1, REQ_IMPORTAR = 2;
    private static final String[] FICHEIROS = {"mapa.png", "rota.txt", "ruas.bin"};
    private static final int[] CORES = {
            Color.argb(158, 0, 0, 0),        // à espera
            Color.argb(225, 13, 69, 140),    // azul: normal
            Color.argb(240, 245, 133, 0),    // laranja: manobra a < 100 m
            Color.argb(235, 199, 26, 26)};   // vermelho: fora da rota

    private MapaView mapa;
    private TextView info, nav;
    private Button btnSeguir;
    private GradientDrawable fundoNav;
    private final ExecutorService trabalho = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LocationManager lm;
    private boolean gpsLigado, seguir;
    private String gpsEstado = "GPS a iniciar…";
    private int satUsados = -1, satVistos = -1;

    private File pasta;
    private Geo.Meta meta;
    private double[] destino;
    private String destNome = "Destino";
    private Navegacao navegacao;
    private BaseRuas ruas;
    private String estadoRuas = "";
    private double[] eu;     // {lat, lon, precisão, velocidade, rumo}

    // ------------------------------------------------------------ interface
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable fundo(int cor, float raioDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(raioDp));
        return g;
    }

    private TextView caixa(float sp) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        return t;
    }

    private Button botao(String texto, View.OnClickListener acao) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setBackground(fundo(Color.argb(225, 32, 32, 32), 6));
        b.setOnClickListener(acao);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setFitsSystemWindows(true);
        raiz.setBackgroundColor(Color.rgb(30, 30, 30));

        mapa = new MapaView(this);
        raiz.addView(mapa, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = caixa(13.5f);
        info.setGravity(Gravity.CENTER);
        info.setBackground(fundo(CORES[0], 8));
        FrameLayout.LayoutParams lpi = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lpi.setMargins(dp(8), dp(6), dp(8), 0);
        raiz.addView(info, lpi);

        LinearLayout baixo = new LinearLayout(this);
        baixo.setOrientation(LinearLayout.VERTICAL);
        nav = caixa(16);
        nav.setMinHeight(dp(84));
        nav.setGravity(Gravity.CENTER_VERTICAL);
        fundoNav = fundo(CORES[0], 10);
        nav.setBackground(fundoNav);
        LinearLayout.LayoutParams lpn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpn.setMargins(dp(6), 0, dp(6), dp(6));
        baixo.addView(nav, lpn);

        LinearLayout botoes = new LinearLayout(this);
        botoes.setOrientation(LinearLayout.HORIZONTAL);
        botoes.addView(botao("Onde\nestou", v -> centrarEmMim()));
        botoes.addView(botao("Destino", v -> { if (destino != null) mapa.centrar(destino[0], destino[1]); }));
        btnSeguir = botao("Seguir:\nnão", v -> alternarSeguir());
        botoes.addView(btnSeguir);
        botoes.addView(botao("Mapa\ntodo", v -> mapa.verTudo()));
        botoes.addView(botao("Importar", v -> importar()));
        botoes.addView(botao("Recar-\nregar", v -> carregar()));
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpb.setMargins(dp(4), 0, dp(4), dp(6));
        baixo.addView(botoes, lpb);
        raiz.addView(baixo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        View.OnLayoutChangeListener margens = (v, l, t, r, bt, ol, ot, or, ob) ->
                mapa.setMargens(info.getBottom() - mapa.getTop(), baixo.getTop() - mapa.getTop());
        info.addOnLayoutChangeListener(margens);
        baixo.addOnLayoutChangeListener(margens);

        setContentView(raiz);

        File ext = getExternalFilesDir(null);   // cria a pasta se não existir
        pasta = ext != null ? ext : getFilesDir();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        carregar();
        pedirGps();
    }

    @Override
    protected void onDestroy() {
        pararGps();
        trabalho.shutdownNow();
        super.onDestroy();
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show();
    }

    private void centrarEmMim() {
        if (eu != null) mapa.centrar(eu[0], eu[1]);
        else toast("Ainda sem posição GPS.");
    }

    private void alternarSeguir() {
        seguir = !seguir;
        btnSeguir.setText(seguir ? "Seguir:\nsim" : "Seguir:\nnão");
        if (seguir) centrarEmMim();
    }

    // ------------------------------------------------------------ ficheiros
    private static final class Carregado {
        Bitmap bitmap;
        Geo.Meta meta;
        List<Geo.Ponto> pontos = new ArrayList<>();
        double[] destino;
        String destNome = "Destino";
        Navegacao nav;
        String erro;
    }

    private void carregar() {
        info.setText("A carregar o mapa…");
        trabalho.execute(() -> {
            Carregado c = new Carregado();
            try {
                File png = new File(pasta, "mapa.png");
                if (!png.isFile()) {
                    c.erro = "sem mapa";
                } else {
                    Map<String, String> textos = Ficheiros.textosPng(png);
                    String geo = textos.get("rota_geo");
                    if (geo == null) throw new IllegalArgumentException(
                            "Este mapa.png não tem calibração (foi editado ou não veio da app do PC).");
                    c.meta = Geo.Meta.de(Json.objeto(geo));
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    c.bitmap = BitmapFactory.decodeFile(png.getAbsolutePath(), o);
                    if (c.bitmap == null) throw new IllegalArgumentException("Não consegui abrir o mapa.png.");
                    if (textos.get("rota_nav") != null) {
                        try {
                            c.nav = Navegacao.deJson(textos.get("rota_nav"));
                        } catch (RuntimeException e) {
                            c.nav = null;    // mapa antigo sem traçado: só sem avisos de manobra
                        }
                    }
                    File txt = new File(pasta, "rota.txt");
                    if (txt.isFile()) c.pontos = Ficheiros.lerRota(txt);
                    Geo.Ponto dest = null;
                    for (Geo.Ponto p : c.pontos)
                        if (p.nome.toUpperCase(Locale.ROOT).startsWith("[DESTINO]")) dest = p;
                    if (dest == null && !c.pontos.isEmpty()) dest = c.pontos.get(c.pontos.size() - 1);
                    if (dest != null) {
                        c.destino = new double[]{dest.lat, dest.lon};
                        c.destNome = dest.nome.replace("[DESTINO]", "").trim();
                    } else {
                        c.destino = new double[]{c.meta.destLat, c.meta.destLon};
                        c.destNome = c.meta.destNome;
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                c.erro = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            ui.post(() -> aplicar(c));
        });
    }

    private void aplicar(Carregado c) {
        ruas = null;
        estadoRuas = "";
        if (c.erro != null || c.meta == null) {
            meta = null;
            navegacao = null;
            destino = null;
            mapa.setMapa(null, null, null, null);
            String porque = "sem mapa".equals(c.erro) ? "<b>Ainda não há mapa.</b>" : "<b>Erro:</b> " + Geo.html(c.erro);
            info.setText(Html.fromHtml(porque + "<br>Carrega em <b>Importar</b> e escolhe a pasta onde estão "
                    + "mapa.png, rota.txt e ruas.bin (ex.: Download/rota),<br>ou copia-os por USB para:<br><small>"
                    + Geo.html(pasta.getAbsolutePath()) + "</small>", Html.FROM_HTML_MODE_LEGACY));
            atualizarNav();
            return;
        }
        meta = c.meta;
        navegacao = c.nav;
        destino = c.destino;
        destNome = c.destNome;
        mapa.setMapa(c.bitmap, c.meta, c.pontos, c.destino);
        atualizarTextos();
        final File f = new File(pasta, "ruas.bin");
        if (f.isFile()) {
            estadoRuas = "A carregar as ruas do raio…";
            trabalho.execute(() -> {
                BaseRuas base = null;
                String msg;
                try {
                    base = new BaseRuas(f);
                    msg = base.nomes.length + " ruas do raio carregadas";
                } catch (Exception | OutOfMemoryError e) {
                    msg = "Erro a ler ruas.bin: " + e.getMessage();
                }
                final BaseRuas fb = base;
                final String fm = msg;
                ui.post(() -> {
                    ruas = fb;
                    estadoRuas = fm;
                    atualizarTextos();
                });
            });
        }
    }

    // ------------------------------------------------------------ importar (sem permissões: o utilizador escolhe a pasta)
    private void importar() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMPORTAR);
            toast("Escolhe a pasta onde estão mapa.png, rota.txt e ruas.bin");
        } catch (ActivityNotFoundException e) {
            toast("Este telemóvel não deixa escolher pastas. Copia os ficheiros por USB.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORTAR || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri arvore = data.getData();
        info.setText("A importar…");
        trabalho.execute(() -> {
            String msg = copiarDaPasta(arvore);
            ui.post(() -> {
                toast(msg);
                carregar();
            });
        });
    }

    private String copiarDaPasta(Uri arvore) {
        List<String> feitos = new ArrayList<>();
        try {
            String raiz = DocumentsContract.getTreeDocumentId(arvore);
            Uri filhos = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, raiz);
            try (Cursor cur = getContentResolver().query(filhos, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (cur != null && cur.moveToNext()) {
                    String id = cur.getString(0), nome = cur.getString(1);
                    if (nome == null) continue;
                    for (String alvo : FICHEIROS) {
                        if (!nome.equalsIgnoreCase(alvo)) continue;
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(arvore, id);
                        File tmp = new File(pasta, alvo + ".tmp");
                        try (InputStream in = getContentResolver().openInputStream(doc);
                             OutputStream out = new FileOutputStream(tmp)) {
                            if (in == null) continue;
                            byte[] buf = new byte[1 << 16];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        File fin = new File(pasta, alvo);
                        if (fin.exists() && !fin.delete()) continue;
                        if (tmp.renameTo(fin)) feitos.add(alvo);
                    }
                }
            }
        } catch (Exception e) {
            return "Erro a importar: " + e.getMessage();
        }
        if (!feitos.contains("mapa.png")) {
            if (!feitos.isEmpty()) return "Importei " + feitos + ", mas não encontrei mapa.png nessa pasta.";
            return "Não encontrei mapa.png, rota.txt nem ruas.bin nessa pasta.";
        }
        if (!feitos.contains("ruas.bin")) new File(pasta, "ruas.bin").delete();  // evita ruas de outro mapa
        return "Importado: " + feitos;
    }

    // ------------------------------------------------------------ GPS
    private void pedirGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ligarGps();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_LOC) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            ligarGps();
        } else {
            gpsEstado = "Sem permissão de localização: dá-a nas definições da app.";
            atualizarTextos();
        }
    }

    private final LocationListener ouvinte = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            aoMudarPosicao(l);
        }

        // Em Android 10 e anteriores estes métodos são obrigatórios
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
            gpsEstado = "GPS ligado, à procura de sinal…";
            atualizarTextos();
        }

        @Override
        public void onProviderDisabled(String provider) {
            gpsEstado = "Liga a Localização (GPS) nas definições!";
            atualizarTextos();
        }
    };

    private final GnssStatus.Callback satelites = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus st) {
            int usados = 0;
            for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) usados++;
            satUsados = usados;
            satVistos = st.getSatelliteCount();
            if (eu == null) atualizarTextos();
        }
    };

    @SuppressWarnings({"MissingPermission", "deprecation"})
    private void ligarGps() {
        if (gpsLigado || lm == null) return;
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsEstado = "Liga a Localização (GPS) nas definições!";
            } else {
                gpsEstado = "À procura de satélites… (fica ao ar livre)";
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, ouvinte, Looper.getMainLooper());
            lm.registerGnssStatusCallback(satelites, ui);
            gpsLigado = true;
            Location ultima = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (ultima != null && System.currentTimeMillis() - ultima.getTime() < 120000) aoMudarPosicao(ultima);
        } catch (SecurityException | IllegalArgumentException e) {
            gpsEstado = "Erro GPS: " + e.getMessage();
        }
        atualizarTextos();
    }

    @SuppressWarnings("deprecation")
    private void pararGps() {
        if (!gpsLigado || lm == null) return;
        try {
            lm.removeUpdates(ouvinte);
            lm.unregisterGnssStatusCallback(satelites);
        } catch (RuntimeException ignored) {
        }
        gpsLigado = false;
    }

    private void aoMudarPosicao(Location l) {
        eu = new double[]{l.getLatitude(), l.getLongitude(),
                l.hasAccuracy() ? l.getAccuracy() : 0, l.hasSpeed() ? l.getSpeed() : 0,
                l.hasBearing() ? l.getBearing() : 0};
        gpsEstado = "GPS ok";
        mapa.setEu(eu);
        if (seguir) mapa.centrar(eu[0], eu[1]);
        atualizarTextos();
    }

    // ------------------------------------------------------------ textos
    private void atualizarTextos() {
        if (meta != null) {
            StringBuilder h = new StringBuilder();
            h.append("<font color='#55ee66'>●</font> <b>").append(Geo.html(destNome.length() > 60
                    ? destNome.substring(0, 60) + "…" : destNome)).append("</b>");
            if (eu != null && destino != null) {
                double d = Geo.distancia(eu[0], eu[1], destino[0], destino[1]);
                double r = Geo.rumo(eu[0], eu[1], destino[0], destino[1]);
                h.append("<br><font color='#ff5555'>●</font> Destino a <b>").append(Geo.fmtDist(d))
                        .append("</b> em linha reta, para <b>").append(Geo.cardeal(r)).append("</b> (")
                        .append(String.format(Locale.ROOT, "%.0f°", r)).append(")");
                h.append("<br>").append(String.format(Locale.ROOT, "%.6f, %.6f", eu[0], eu[1]))
                        .append(String.format(Geo.PT, " · ±%.0f m · %.0f km/h", eu[2], eu[3] * 3.6));
                if (!mapa.dentro(eu[0], eu[1]))
                    h.append("<br><font color='#ffaa00'>Estás fora do mapa (seta vermelha na borda).</font>");
            } else {
                h.append("<br>").append(Geo.html(gpsEstado));
                if (satVistos >= 0)
                    h.append(String.format(Geo.PT, "<br><small>satélites: %d visíveis, %d em uso</small>",
                            satVistos, satUsados));
            }
            info.setText(Html.fromHtml(h.toString(), Html.FROM_HTML_MODE_LEGACY));
        }
        atualizarNav();
    }

    private void atualizarNav() {
        Painel pn = Painel.calcular(meta != null, eu == null ? null : new double[]{eu[0], eu[1], eu[2]},
                ruas, navegacao, destino, estadoRuas);
        nav.setVisibility(meta == null ? View.GONE : View.VISIBLE);
        nav.setText(Html.fromHtml(pn.html, Html.FROM_HTML_MODE_LEGACY));
        fundoNav.setColor(CORES[pn.cor]);
    }
}
