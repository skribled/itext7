package com.itextpdf.kernel.font;

import com.itextpdf.io.util.IntHashtable;
import com.itextpdf.kernel.PdfException;
import com.itextpdf.io.util.Utilities;
import com.itextpdf.io.font.CFFFontSubset;
import com.itextpdf.io.font.CMapEncoding;
import com.itextpdf.io.font.CidFont;
import com.itextpdf.io.font.CidFontProperties;
import com.itextpdf.io.font.FontConstants;
import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.io.font.TrueTypeFont;
import com.itextpdf.io.font.cmap.CMapContentParser;
import com.itextpdf.io.font.cmap.CMapToUnicode;
import com.itextpdf.io.font.otf.Glyph;
import com.itextpdf.io.font.otf.GlyphLine;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDictionary;
import com.itextpdf.kernel.pdf.PdfLiteral;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfNumber;
import com.itextpdf.kernel.pdf.PdfOutputStream;
import com.itextpdf.kernel.pdf.PdfStream;
import com.itextpdf.kernel.pdf.PdfString;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class PdfType0Font extends PdfSimpleFont<FontProgram> {

    private static final int[] Empty = {};
    private static final byte[] rotbits = {(byte) 0x80, (byte) 0x40, (byte) 0x20, (byte) 0x10, (byte) 0x08, (byte) 0x04, (byte) 0x02, (byte) 0x01};

    private static final int First = 0;
    private static final int Bracket = 1;
    private static final int Serial = 2;
    private static final int V1y = 880;

    protected static final int CidFontType0 = 0;
    protected static final int CidFontType2 = 2;

    protected boolean vertical;
    protected CMapEncoding cmapEncoding;
    // TODO HashSet will be enough
    protected Map<Integer, int[]> longTag;
    protected int cidFontType;
    protected char[] specificUnicodeDifferences;

    PdfType0Font(TrueTypeFont ttf, String cmap) {
        super();
        if (!cmap.equals(PdfEncodings.IDENTITY_H) && !cmap.equals(PdfEncodings.IDENTITY_V)) {
            throw new PdfException("only.identity.cmaps.supports.with.truetype");
        }

        if (!ttf.getFontNames().allowEmbedding()) {
            throw new PdfException("1.cannot.be.embedded.due.to.licensing.restrictions")
                    .setMessageParams(ttf.getFontNames().getFontName() + ttf.getFontNames().getStyle());
        }
        this.fontProgram = ttf;
        this.embedded = true;
        vertical = cmap.endsWith(FontConstants.V_SYMBOL);
        cmapEncoding = new CMapEncoding(cmap);
        longTag = new LinkedHashMap<>();
        cidFontType = CidFontType2;
        if (ttf.isFontSpecific()) {
            specificUnicodeDifferences = new char[256];
            byte[] bytes = new byte[1];
            for (int k = 0; k < 256; ++k) {
                bytes[0] = (byte) k;
                String s = PdfEncodings.convertToString(bytes, null);
                char ch = s.length() > 0 ? s.charAt(0) : '?';
                specificUnicodeDifferences[k] = ch;
            }
        }
    }

    //note Make this constructor protected. Only FontFactory (kernel level) will
    // be able to create Type0 font based on predefined font.
    // Or not? Possible it will be convenient construct PdfType0Font based on custom CidFont.
    // There is no typography features in CJK fonts.
    PdfType0Font(CidFont font, String cmap) {
        super();
        if (!CidFontProperties.isCidFont(font.getFontNames().getFontName(), cmap)) {
            throw new PdfException("font.1.with.2.encoding.is.not.a.cjk.font")
                    .setMessageParams(font.getFontNames().getFontName(), cmap);
        }
        this.fontProgram = font;
        vertical = cmap.endsWith("V");
        String uniMap = getUniMapName(fontProgram.getRegistry());
        cmapEncoding = new CMapEncoding(cmap, uniMap);
        longTag = new LinkedHashMap<>();
        cidFontType = CidFontType0;
    }

    PdfType0Font(PdfDictionary fontDictionary) {
        super(fontDictionary);
        checkFontDictionary(fontDictionary, PdfName.Type0);
        newFont = false;
        CMapToUnicode toUnicodeCMap = FontUtils.processToUnicode(fontDictionary.get(PdfName.ToUnicode));
        PdfDictionary descendantFont = fontDictionary.getAsArray(PdfName.DescendantFonts).getAsDictionary(0);
        fontProgram = DocTrueTypeFont.createFontProgram(descendantFont, toUnicodeCMap);
        cmapEncoding = new CMapEncoding(PdfEncodings.IDENTITY_H);
        cidFontType = CidFontType2;
        assert fontProgram instanceof DocFontProgram;
        embedded = ((DocFontProgram) fontProgram).getFontFile() != null;
        longTag = new LinkedHashMap<>();
        subset = false;
    }

    protected String getUniMapName(String registry) {
        String uniMap = "";
        for (String name : CidFontProperties.getRegistryNames().get(registry + "_Uni")) {
            uniMap = name;
            if (name.endsWith(FontConstants.V_SYMBOL) && vertical) {
                break;
            } else if (!name.endsWith(FontConstants.V_SYMBOL) && !vertical) {
                break;
            }
        }
        return uniMap;
    }

    @Override
    public Glyph getGlyph(int unicode) {
        // TODO handle unicode value with cmap and use only glyphByCode
        Glyph glyph = getFontProgram().getGlyph(unicode);
        if (glyph == null && (glyph = notdefGlyphs.get(unicode)) == null) {
            // Handle special layout characters like sfthyphen (00AD).
            // This glyphs will be skipped while converting to bytes
            Glyph notdef = getFontProgram().getGlyphByCode(0);
            if (notdef != null) {
                glyph = new Glyph(notdef, unicode);
            } else {
                glyph = new Glyph(-1, 0, unicode);
            }
            notdefGlyphs.put(unicode, glyph);
        }
        return glyph;
    }

    @Override
    public byte[] convertToBytes(String text) {
        //TODO different with type0 and type2 could be removed after simplifying longTag
        if (cidFontType == CidFontType0) {
            int len = text.length();
            if (isIdentity()) {
                for (int k = 0; k < len; ++k) {
                    longTag.put((int) text.charAt(k), Empty);
                }
            } else {
                for (int k = 0; k < len; ++k) {
                    int ch;
                    if (Utilities.isSurrogatePair(text, k)) {
                        ch = Utilities.convertToUtf32(text, k);
                        k++;
                    } else {
                        ch = text.charAt(k);
                    }
                    longTag.put(cmapEncoding.getCidCode(ch), Empty);
                }
            }
            return cmapEncoding.convertToBytes(text);
        } else if (cidFontType == CidFontType2) {
            TrueTypeFont ttf = (TrueTypeFont) fontProgram;
            int len = text.length();
            char[] glyphs = new char[len];
            int i = 0;
            if (ttf.isFontSpecific()) {
                byte[] b = PdfEncodings.convertToBytes(text, "symboltt");
                len = b.length;
                for (int k = 0; k < len; ++k) {
                    Glyph glyph = fontProgram.getGlyph(b[k] & 0xff);
                    if (glyph != null && !longTag.containsKey(glyph.getCode())) {
                        longTag.put(glyph.getCode(), new int[]{glyph.getCode(), glyph.getWidth(),
                                glyph.getUnicode() != null ? glyph.getUnicode() : 0});
                        glyphs[i++] = (char) glyph.getCode();
                    }
                }
            } else {
                for (int k = 0; k < len; ++k) {
                    int val;
                    if (Utilities.isSurrogatePair(text, k)) {
                        val = Utilities.convertToUtf32(text, k);
                        k++;
                    } else {
                        val = text.charAt(k);
                    }
                    Glyph glyph = fontProgram.getGlyph(val);
                    if (glyph == null) {
                        glyph = fontProgram.getGlyphByCode(0);
                    }
                    if (!longTag.containsKey(glyph.getCode())) {
                        longTag.put(glyph.getCode(), new int[]{glyph.getCode(), glyph.getWidth(),
                                glyph.getUnicode() != null ? glyph.getUnicode() : 0});
                    }
                    glyphs[i++] = (char) glyph.getCode();
                }
            }

            String s = new String(glyphs, 0, i);
            try {
                return s.getBytes(PdfEncodings.UnicodeBigUnmarked);
            } catch (UnsupportedEncodingException e) {
                throw new PdfException("TrueTypeFont", e);
            }
        } else {
            throw new PdfException("font.has.no.suitable.cmap");
        }
    }

    @Override
    public byte[] convertToBytes(GlyphLine glyphLine) {
        if (glyphLine != null) {
            char[] glyphs = new char[glyphLine.glyphs.size()];
            for (int i = 0; i < glyphLine.glyphs.size(); i++) {
                Glyph glyph = glyphLine.glyphs.get(i);
                glyphs[i] = (char) glyph.getCode();
                int code = glyph.getCode();
                if (longTag.get(code) == null) {
                    Integer uniChar = glyph.getUnicode();
                    longTag.put(code, new int[]{code, glyph.getWidth(), uniChar != null ? uniChar : 0});
                }
            }

            String s = new String(glyphs, 0, glyphs.length);
            try {
                return s.getBytes(PdfEncodings.UnicodeBigUnmarked);
            } catch (UnsupportedEncodingException e) {
                throw new PdfException("TrueTypeFont", e);
            }
        } else {
            return null;
        }
    }

    @Override
    public byte[] convertToBytes(Glyph glyph) {
        int code = glyph.getCode();
        if (longTag.get(code) == null) {
            longTag.put(code, new int[]{code, glyph.getWidth(), glyph.getUnicode() != null ? glyph.getUnicode() : 0});
        }
        String s = new String(new char[]{(char) glyph.getCode()}, 0, 1);
        try {
            return s.getBytes(PdfEncodings.UnicodeBigUnmarked);
        } catch (UnsupportedEncodingException e) {
            throw new PdfException("PdfType0Font", e);
        }
    }

    @Override
    public void writeText(GlyphLine text, int from, int to, PdfOutputStream stream) {
        StringBuilder bytes = new StringBuilder();
        for (int i = from; i <= to; i++) {
            Glyph glyph = text.get(i);
            int code = glyph.getCode();
            bytes.append((char) glyph.getCode());

            if (longTag.get(code) == null) {
                longTag.put(code, new int[]{code, glyph.getWidth(), glyph.getUnicode() != null ? glyph.getUnicode() : 0});
            }

        }
        //TODO improve converting chars to hexed string
        try {
            Utilities.writeHexedString(stream, bytes.toString().getBytes(PdfEncodings.UnicodeBigUnmarked));
        } catch (UnsupportedEncodingException e) {
            throw new PdfException("PdfType0Font", e);
        }
    }

    @Override
    public void writeText(String text, PdfOutputStream stream) {
        Utilities.writeHexedString(stream, convertToBytes(text));
    }

    @Override
    public GlyphLine createGlyphLine(String content) {
        List<Glyph> glyphs = new ArrayList<>();
        //TODO different with type0 and type2 could be removed after simplifying longTag
        if (cidFontType == CidFontType0) {
            int len = content.length();
            if (isIdentity()) {
                for (int k = 0; k < len; ++k) {
                    Glyph glyph = fontProgram.getGlyphByCode((int) content.charAt(k));
                    if (glyph != null) {
                        glyphs.add(glyph);
                    }
                }
            } else {
                for (int k = 0; k < len; ++k) {
                    int ch;
                    if (Utilities.isSurrogatePair(content, k)) {
                        ch = Utilities.convertToUtf32(content, k);
                        k++;
                    } else {
                        ch = content.charAt(k);
                    }
                    glyphs.add(getGlyph(ch));
                }
            }
        } else if (cidFontType == CidFontType2) {
            TrueTypeFont ttf = (TrueTypeFont) fontProgram;
            int len = content.length();

            if (ttf.isFontSpecific()) {
                byte[] b = PdfEncodings.convertToBytes(content, "symboltt");
                len = b.length;
                for (int k = 0; k < len; ++k) {
                    Glyph glyph = fontProgram.getGlyph(b[k] & 0xff);
                    if (glyph != null) {
                        glyphs.add(glyph);
                    }
                }
            } else {
                for (int k = 0; k < len; ++k) {
                    int val;
                    if (Utilities.isSurrogatePair(content, k)) {
                        val = Utilities.convertToUtf32(content, k);
                        k++;
                    } else {
                        val = content.charAt(k);
                    }
                    glyphs.add(getGlyph(val));
                }
            }
        } else {
            throw new PdfException("font.has.no.suitable.cmap");
        }

        return new GlyphLine(glyphs);
    }

    @Override
    public String decode(PdfString content) {
        //TODO now we support only identity-h
        String cids = content.getValue();
        StringBuilder builder = new StringBuilder(cids.length() / 2);
        for (int i = 0; i < cids.length(); i++) {
            int code = cids.charAt(i++);
            if (i == cids.length()) {
                //allowed only two bytes per code
                continue;
            }
            code <<= 8;
            code |= cids.charAt(i);
            Glyph glyph = fontProgram.getGlyphByCode(code);
            if (glyph != null && glyph.getUnicode() != null) {
                builder.append((char) (int) glyph.getUnicode());
            } else {
                builder.append('?');
            }
        }
        return builder.toString();
    }

    @Override
    public float getContentWidth(PdfString content) {
        //TODO now we support only identity-h
        String cids = content.getValue();
        Glyph notdef = fontProgram.getGlyphByCode(0);
        float width = 0;
        for (int i = 0; i < cids.length(); i++) {
            int code = cids.charAt(i++);
            if (i < cids.length()) {
                code <<= 8;
                code |= cids.charAt(i);
            }
            Glyph glyph = fontProgram.getGlyphByCode(code);
            width += glyph != null ? glyph.getWidth() : notdef.getWidth();
        }
        return width;
    }

    @Override
    protected PdfDictionary getFontDescriptor(String fontName) {
        PdfDictionary fontDescriptor = new PdfDictionary();
        markObjectAsIndirect(fontDescriptor);
        fontDescriptor.put(PdfName.Type, PdfName.FontDescriptor);
        fontDescriptor.put(PdfName.FontName, new PdfName(fontName));
        fontDescriptor.put(PdfName.FontBBox, new PdfArray(getFontProgram().getFontMetrics().getBbox()));
        fontDescriptor.put(PdfName.Ascent, new PdfNumber(getFontProgram().getFontMetrics().getTypoAscender()));
        fontDescriptor.put(PdfName.Descent, new PdfNumber(getFontProgram().getFontMetrics().getTypoDescender()));
        fontDescriptor.put(PdfName.CapHeight, new PdfNumber(getFontProgram().getFontMetrics().getCapHeight()));
        fontDescriptor.put(PdfName.ItalicAngle, new PdfNumber(getFontProgram().getFontMetrics().getItalicAngle()));
        fontDescriptor.put(PdfName.StemV, new PdfNumber(getFontProgram().getFontMetrics().getStemV()));
        fontDescriptor.put(PdfName.Flags, new PdfNumber(getFontProgram().getPdfFontFlags()));
        if (fontProgram.getFontIdentification().getPanose() != null) {
            PdfDictionary styleDictionary = new PdfDictionary();
            styleDictionary.put(PdfName.Panose, new PdfString(fontProgram.getFontIdentification().getPanose()).setHexWriting(true));
            fontDescriptor.put(PdfName.Style, styleDictionary);
        }

        return fontDescriptor;
    }

    public boolean isIdentity() {
        //TODO strange property
        return cmapEncoding.isDirect();
    }

    @Override
    public void flush() {
        if (newFont) {
            flushFontData();
        }
        super.flush();
    }

    @Override //TODO
    protected void addFontStream(PdfDictionary fontDescriptor) {
    }

    private void flushFontData() {
        if (cidFontType == CidFontType0) {
            getPdfObject().put(PdfName.Type, PdfName.Font);
            getPdfObject().put(PdfName.Subtype, PdfName.Type0);
            String name = fontProgram.getFontNames().getFontName();
            String style = fontProgram.getFontNames().getStyle();
            if (style.length() > 0) {
                name += "-" + style;
            }
            getPdfObject().put(PdfName.BaseFont, new PdfName(String.format("%s-%s", name, cmapEncoding.getCmapName())));
            getPdfObject().put(PdfName.Encoding, new PdfName(cmapEncoding.getCmapName()));
            PdfDictionary fontDescriptor = getFontDescriptor(name);
            PdfDictionary cidFont = getCidFontType0(fontDescriptor);
            getPdfObject().put(PdfName.DescendantFonts, new PdfArray(cidFont));
            fontDescriptor.flush();
            cidFont.flush();
        } else if (cidFontType == CidFontType2) {
            TrueTypeFont ttf = (TrueTypeFont) getFontProgram();
            addRangeUni(ttf, longTag, true);
            int[][] metrics = longTag.values().toArray(new int[0][]);
            Arrays.sort(metrics, new MetricComparator());
            PdfStream fontStream;
            String fontName = ttf.getFontNames().getFontName();
            if (subset) {
                fontName = createSubsetPrefix() + fontName;
            }
            PdfDictionary fontDescriptor = getFontDescriptor(fontName);
            if (ttf.isCff()) {
                byte[] cffBytes = ttf.getFontStreamBytes();
                if (subset || subsetRanges != null) {
                    CFFFontSubset cff = new CFFFontSubset(ttf.getFontStreamBytes(), longTag);
                    cffBytes = cff.Process(cff.getNames()[0]);
                }
                fontStream = getPdfFontStream(cffBytes, new int[]{cffBytes.length});
                // The PDF Reference manual advises to add -cmap in case CIDFontType0
                getPdfObject().put(PdfName.BaseFont,
                        new PdfName(String.format("%s-%s", fontName, cmapEncoding.getCmapName())));
                fontDescriptor.put(PdfName.Subtype, new PdfName("CIDFontType0C"));
                fontDescriptor.put(PdfName.FontFile3, fontStream);
            } else {
                byte[] ttfBytes;
                if (subset || ttf.getDirectoryOffset() != 0) {
                    ttfBytes = ttf.getSubset(new LinkedHashSet<>(longTag.keySet()), true);
                } else {
                    ttfBytes = ttf.getFontStreamBytes();
                }
                fontStream = getPdfFontStream(ttfBytes, new int[]{ttfBytes.length});
                getPdfObject().put(PdfName.BaseFont, new PdfName(fontName));
                fontDescriptor.put(PdfName.FontFile2, fontStream);
            }

            // CIDSet shall be based on font.maxGlyphId property of the font, it is maxp.numGlyphs for ttf,
            // because technically we convert all unused glyphs to space, e.g. just remove outlines.
            int maxGlyphId = ttf.getFontMetrics().getMaxGlyphId();
            byte[] cidSetBytes = new byte[ttf.getFontMetrics().getMaxGlyphId() / 8 + 1];
            for (int i = 0; i < maxGlyphId / 8; i++) {
                cidSetBytes[i] |= 0xff;
            }
            for (int i = 0; i < maxGlyphId % 8; i++) {
                cidSetBytes[cidSetBytes.length - 1] |= rotbits[i];
            }
            fontDescriptor.put(PdfName.CIDSet, new PdfStream(cidSetBytes));
            PdfDictionary cidFont = getCidFontType2(ttf, fontDescriptor, fontName, metrics);

            getPdfObject().put(PdfName.Type, PdfName.Font);
            getPdfObject().put(PdfName.Subtype, PdfName.Type0);
            getPdfObject().put(PdfName.Encoding, new PdfName(cmapEncoding.getCmapName()));
            getPdfObject().put(PdfName.DescendantFonts, new PdfArray(cidFont));

            PdfStream toUnicode = getToUnicode(metrics);
            if (toUnicode != null) {
                getPdfObject().put(PdfName.ToUnicode, toUnicode);
                toUnicode.flush();
            }
            fontDescriptor.flush();
            cidFont.flush();
        } else {
            throw new IllegalStateException("Unsupported CID Font");
        }
    }


    /**
     * Generates the CIDFontTyte2 dictionary.
     *
     * @param fontDescriptor the indirect reference to the font descriptor
     * @param fontName       a name of the font
     * @param metrics        the horizontal width metrics
     * @return a stream
     */
    public PdfDictionary getCidFontType2(TrueTypeFont ttf, PdfDictionary fontDescriptor, String fontName, int[][] metrics) {
        PdfDictionary cidFont = new PdfDictionary();
        markObjectAsIndirect(cidFont);
        cidFont.put(PdfName.Type, PdfName.Font);
        // sivan; cff
        cidFont.put(PdfName.FontDescriptor, fontDescriptor);
        if (ttf.isCff()) {
            cidFont.put(PdfName.Subtype, PdfName.CIDFontType0);
        } else {
            cidFont.put(PdfName.Subtype, PdfName.CIDFontType2);
            cidFont.put(PdfName.CIDToGIDMap, PdfName.Identity);
        }
        cidFont.put(PdfName.BaseFont, new PdfName(fontName));
        PdfDictionary cidInfo = new PdfDictionary();
        cidInfo.put(PdfName.Registry, new PdfString("Adobe"));
        cidInfo.put(PdfName.Ordering, new PdfString("Identity"));
        cidInfo.put(PdfName.Supplement, new PdfNumber(0));
        cidFont.put(PdfName.CIDSystemInfo, cidInfo);
        if (!vertical) {
            cidFont.put(PdfName.DW, new PdfNumber(FontProgram.DEFAULT_WIDTH));
            StringBuilder buf = new StringBuilder("[");
            int lastNumber = -10;
            boolean firstTime = true;
            for (int[] metric : metrics) {
                if (metric[1] == FontProgram.DEFAULT_WIDTH) {
                    continue;
                }
                if (metric[0] == lastNumber + 1) {
                    buf.append(' ').append(metric[1]);
                } else {
                    if (!firstTime) {
                        buf.append(']');
                    }
                    firstTime = false;
                    buf.append(metric[0]).append('[').append(metric[1]);
                }
                lastNumber = metric[0];
            }
            if (buf.length() > 1) {
                buf.append("]]");
                cidFont.put(PdfName.W, new PdfLiteral(buf.toString()));
            }
        }
        return cidFont;
    }

    /**
     * Creates a ToUnicode CMap to allow copy and paste from Acrobat.
     *
     * @param metrics metrics[0] contains the glyph index and metrics[2]
     *                contains the Unicode code
     * @return the stream representing this CMap or <CODE>null</CODE>
     */
    public PdfStream getToUnicode(Object[] metrics) {
        if (metrics.length == 0)
            return null;
        StringBuilder buf = new StringBuilder(
                "/CIDInit /ProcSet findresource begin\n" +
                        "12 dict begin\n" +
                        "begincmap\n" +
                        "/CIDSystemInfo\n" +
                        "<< /Registry (Adobe)\n" +
                        "/Ordering (UCS)\n" +
                        "/Supplement 0\n" +
                        ">> def\n" +
                        "/CMapName /Adobe-Identity-UCS def\n" +
                        "/CMapType 2 def\n" +
                        "1 begincodespacerange\n" +
                        "<0000><FFFF>\n" +
                        "endcodespacerange\n");
        int size = 0;
        for (int k = 0; k < metrics.length; ++k) {
            if (size == 0) {
                if (k != 0) {
                    buf.append("endbfrange\n");
                }
                size = Math.min(100, metrics.length - k);
                buf.append(size).append(" beginbfrange\n");
            }
            --size;
            int[] metric = (int[]) metrics[k];
            String fromTo = CMapContentParser.toHex(metric[0]);
            Glyph glyph = fontProgram.getGlyphByCode(metric[0]);
            if (glyph.getChars() != null) {
                StringBuilder uni = new StringBuilder(glyph.getChars().length);
                for (char ch : glyph.getChars()) {
                    uni.append(toHex4(ch));
                }
                buf.append(fromTo).append(fromTo).append('<').append(uni.toString()).append('>').append('\n');
            }
        }
        buf.append("endbfrange\n" +
                "endcmap\n" +
                "CMapName currentdict /CMap defineresource pop\n" +
                "end end\n");
        return new PdfStream(PdfEncodings.convertToBytes(buf.toString(), null));
    }

    //TODO optimize memory ussage
    private static String toHex4(char ch) {
        String s = "0000" + Integer.toHexString(ch);
        return s.substring(s.length() - 4);
    }

    protected static String convertToHCIDMetrics(int keys[], IntHashtable h) {
        if (keys.length == 0)
            return null;
        int lastCid = 0;
        int lastValue = 0;
        int start;
        for (start = 0; start < keys.length; ++start) {
            lastCid = keys[start];
            lastValue = h.get(lastCid);
            if (lastValue != 0) {
                ++start;
                break;
            }
        }
        if (lastValue == 0) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        buf.append('[');
        buf.append(lastCid);
        int state = First;
        for (int k = start; k < keys.length; ++k) {
            int cid = keys[k];
            int value = h.get(cid);
            if (value == 0) {
                continue;
            }
            switch (state) {
                case First: {
                    if (cid == lastCid + 1 && value == lastValue) {
                        state = Serial;
                    } else if (cid == lastCid + 1) {
                        state = Bracket;
                        buf.append('[').append(lastValue);
                    } else {
                        buf.append('[').append(lastValue).append(']').append(cid);
                    }
                    break;
                }
                case Bracket: {
                    if (cid == lastCid + 1 && value == lastValue) {
                        state = Serial;
                        buf.append(']').append(lastCid);
                    } else if (cid == lastCid + 1) {
                        buf.append(' ').append(lastValue);
                    } else {
                        state = First;
                        buf.append(' ').append(lastValue).append(']').append(cid);
                    }
                    break;
                }
                case Serial: {
                    if (cid != lastCid + 1 || value != lastValue) {
                        buf.append(' ').append(lastCid).append(' ').append(lastValue).append(' ').append(cid);
                        state = First;
                    }
                    break;
                }
            }
            lastValue = value;
            lastCid = cid;
        }
        switch (state) {
            case First: {
                buf.append('[').append(lastValue).append("]]");
                break;
            }
            case Bracket: {
                buf.append(' ').append(lastValue).append("]]");
                break;
            }
            case Serial: {
                buf.append(' ').append(lastCid).append(' ').append(lastValue).append(']');
                break;
            }
        }
        return buf.toString();
    }

    protected static String convertToVCIDMetrics(int keys[], IntHashtable v, IntHashtable h) {
        if (keys.length == 0) {
            return null;
        }
        int lastCid = 0;
        int lastValue = 0;
        int lastHValue = 0;
        int start;
        for (start = 0; start < keys.length; ++start) {
            lastCid = keys[start];
            lastValue = v.get(lastCid);
            if (lastValue != 0) {
                ++start;
                break;
            } else {
                lastHValue = h.get(lastCid);
            }
        }
        if (lastValue == 0) {
            return null;
        }
        if (lastHValue == 0) {
            lastHValue = FontProgram.DEFAULT_WIDTH;
        }
        StringBuilder buf = new StringBuilder();
        buf.append('[');
        buf.append(lastCid);
        int state = First;
        for (int k = start; k < keys.length; ++k) {
            int cid = keys[k];
            int value = v.get(cid);
            if (value == 0) {
                continue;
            }
            int hValue = h.get(lastCid);
            if (hValue == 0) {
                hValue = FontProgram.DEFAULT_WIDTH;
            }
            switch (state) {
                case First: {
                    if (cid == lastCid + 1 && value == lastValue && hValue == lastHValue) {
                        state = Serial;
                    } else {
                        buf.append(' ').append(lastCid).append(' ').append(-lastValue).append(' ').append(lastHValue / 2).append(' ').append(V1y).append(' ').append(cid);
                    }
                    break;
                }
                case Serial: {
                    if (cid != lastCid + 1 || value != lastValue || hValue != lastHValue) {
                        buf.append(' ').append(lastCid).append(' ').append(-lastValue).append(' ').append(lastHValue / 2).append(' ').append(V1y).append(' ').append(cid);
                        state = First;
                    }
                    break;
                }
            }
            lastValue = value;
            lastCid = cid;
            lastHValue = hValue;
        }
        buf.append(' ').append(lastCid).append(' ').append(-lastValue).append(' ').append(lastHValue / 2).append(' ').append(V1y).append(" ]");
        return buf.toString();
    }

    protected void addRangeUni(TrueTypeFont ttf, Map<Integer, int[]> longTag, boolean includeMetrics) {
        if (!subset && (subsetRanges != null || ttf.getDirectoryOffset() > 0)) {
            int[] rg = subsetRanges == null && ttf.getDirectoryOffset() > 0
                    ? new int[]{0, 0xffff} : compactRanges(subsetRanges);
            Map<Integer, int[]> usemap = ttf.getActiveCmap();
            assert usemap != null;
            for (Map.Entry<Integer, int[]> e : usemap.entrySet()) {
                int[] v = e.getValue();
                Integer gi = v[0];
                if (longTag.containsKey(v[0])) {
                    continue;
                }
                int c = e.getKey();
                boolean skip = true;
                for (int k = 0; k < rg.length; k += 2) {
                    if (c >= rg[k] && c <= rg[k + 1]) {
                        skip = false;
                        break;
                    }
                }
                if (!skip) {
                    longTag.put(gi, includeMetrics ? new int[]{v[0], v[1], c} : null);
                }
            }
        }
    }

//    protected void init() {
//        //TODO add CidSet and Panose separately.
//        PdfName baseFont = getPdfObject().getAsName(PdfName.BaseFont);
//        getPdfObject().put(PdfName.Subtype, getPdfObject().getAsName(PdfName.Subtype));
//        getPdfObject().put(PdfName.BaseFont, baseFont);
//        PdfName encoding = getPdfObject().getAsName(PdfName.Encoding);
//        getPdfObject().put(PdfName.Encoding, encoding);
//
//        initFontProgramData();
//
//        PdfDictionary toCidFont = new PdfDictionary();
//        PdfArray fromCidFontArray = getPdfObject().getAsArray(PdfName.DescendantFonts);
//        PdfDictionary fromCidFont = fromCidFontArray.getAsDictionary(0);
//        if (fromCidFont != null) {
//            toCidFont.makeIndirect(getDocument());
//            PdfName subType = fromCidFont.getAsName(PdfName.Subtype);
//            PdfName cidBaseFont = fromCidFont.getAsName(PdfName.BaseFont);
//            PdfObject cidToGidMap = fromCidFont.get(PdfName.CIDToGIDMap);
//            PdfArray w = fromCidFont.getAsArray(PdfName.W);
//            PdfArray w2 = fromCidFont.getAsArray(PdfName.W2);
//            Integer dw = fromCidFont.getAsInt(PdfName.DW);
//
//            toCidFont.put(PdfName.Type, PdfName.Font);
//            toCidFont.put(PdfName.Subtype, subType);
//            toCidFont.put(PdfName.BaseFont, cidBaseFont);
//            fontProgram.getFontNames().setFontName(cidBaseFont.getValue());
//            PdfDictionary fromDescriptorDictionary = fromCidFont.getAsDictionary(PdfName.FontDescriptor);
//            if (fromDescriptorDictionary != null) {
//                PdfDictionary toDescriptorDictionary = getNewFontDescriptor(fromDescriptorDictionary);
//                toCidFont.put(PdfName.FontDescriptor, toDescriptorDictionary);
//                toDescriptorDictionary.flush();
//            }
//
//            if (w != null) {
//                toCidFont.put(PdfName.W, w);
//                if (fontProgram instanceof CidFont) {
//                    ((CidFont) fontProgram).setHMetrics(readWidths(w));
//                }
//            }
//
//            if (w2 != null) {
//                toCidFont.put(PdfName.W2, w2);
//                if (fontProgram instanceof CidFont) {
//                    ((CidFont) fontProgram).setVMetrics(readWidths(w2));
//                }
//            }
//
//            if (dw != null) {
//                toCidFont.put(PdfName.DW, new PdfNumber(dw));
//            }
//
//            if (cidToGidMap != null) {
//                toCidFont.put(PdfName.CIDToGIDMap, cidToGidMap);
//            }
//
//            PdfDictionary toCidInfo = new PdfDictionary();
//            PdfDictionary fromCidInfo = fromCidFont.getAsDictionary(PdfName.CIDSystemInfo);
//            if (fromCidInfo != null) {
//                PdfString registry = fromCidInfo.getAsString(PdfName.Registry);
//                PdfString ordering = fromCidInfo.getAsString(PdfName.Ordering);
//                Integer supplement = fromCidInfo.getAsInt(PdfName.Supplement);
//
//                toCidInfo.put(PdfName.Registry, registry);
//                fontProgram.setRegistry(registry.getValue());
//                toCidInfo.put(PdfName.Ordering, ordering);
//                toCidInfo.put(PdfName.Supplement, new PdfNumber(supplement));
//            }
//            toCidFont.put(PdfName.CIDSystemInfo, fromCidInfo);
//
//            PdfObject toUnicode = getPdfObject().get(PdfName.ToUnicode);
//            if (toUnicode != null) {
//                int dwVal = FontProgram.DEFAULT_WIDTH;
//                if (dw != null) {
//                    dwVal = dw;
//                }
//                IntHashtable widths = readWidths(w);
//                if (toUnicode instanceof PdfStream) {
//                    PdfStream newStream = (PdfStream) toUnicode.clone();
//                    getPdfObject().put(PdfName.ToUnicode, newStream);
//                    newStream.flush();
//                    fillMetrics(((PdfStream) toUnicode).getBytes(), widths, dwVal);
//                } else if (toUnicode instanceof PdfString) {
//                    fillMetricsIdentity(widths, dwVal);
//                }
//            }
//        }
//
//        getPdfObject().put(PdfName.DescendantFonts, new PdfArray(toCidFont));
//        toCidFont.flush();
//    }

    private PdfDictionary getCidFontType0(PdfDictionary fontDescriptor) {
        PdfDictionary cidFont = new PdfDictionary();
        markObjectAsIndirect(cidFont);
        cidFont.put(PdfName.Type, PdfName.Font);
        cidFont.put(PdfName.Subtype, PdfName.CIDFontType0);
        cidFont.put(PdfName.BaseFont, new PdfName(fontProgram.getFontNames().getFontName() + fontProgram.getFontNames().getStyle()));
        cidFont.put(PdfName.FontDescriptor, fontDescriptor);
        int[] keys = Utilities.toArray(longTag.keySet());
        Arrays.sort(keys);
        String w = convertToHCIDMetrics(keys, ((CidFont) fontProgram).getHMetrics());
        if (w != null) {
            cidFont.put(PdfName.W, new PdfLiteral(w));
        }
        if (vertical) {
            w = convertToVCIDMetrics(keys, ((CidFont) fontProgram).getVMetrics(), ((CidFont) fontProgram).getHMetrics());
            if (w != null) {
                cidFont.put(PdfName.W2, new PdfLiteral(w));
            }
        } else {
            cidFont.put(PdfName.DW, new PdfNumber(FontProgram.DEFAULT_WIDTH));
        }
        PdfDictionary cidInfo = new PdfDictionary();
        cidInfo.put(PdfName.Registry, new PdfString(cmapEncoding.getRegistry()));
        cidInfo.put(PdfName.Ordering, new PdfString(cmapEncoding.getOrdering()));
        cidInfo.put(PdfName.Supplement, new PdfNumber(cmapEncoding.getSupplement()));
        cidFont.put(PdfName.CIDSystemInfo, cidInfo);
        return cidFont;
    }

    private static class MetricComparator implements Comparator<int[]> {
        /**
         * The method used to sort the metrics array.
         *
         * @param o1 the first element
         * @param o2 the second element
         * @return the comparison
         */
        public int compare(int[] o1, int[] o2) {
            int m1 = o1[0];
            int m2 = o2[0];
            if (m1 < m2)
                return -1;
            if (m1 == m2)
                return 0;
            return 1;
        }
    }
}