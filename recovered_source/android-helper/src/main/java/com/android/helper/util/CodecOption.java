package com.android.helper.util;

import java.util.ArrayList;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
public class CodecOption {
    private final String key;
    private final Object value;

    public CodecOption(String str, Object obj) {
        this.key = str;
        this.value = obj;
    }

    public String getKey() {
        return this.key;
    }

    public Object getValue() {
        return this.value;
    }

    public static List<CodecOption> parse(String str) {
        if (str.isEmpty()) {
            return null;
        }
        ArrayList arrayList = new ArrayList();
        StringBuilder sb = new StringBuilder();
        boolean z = false;
        for (char c : str.toCharArray()) {
            if (c != ',') {
                if (c != '\\') {
                    sb.append(c);
                } else if (z) {
                    sb.append('\\');
                    z = false;
                } else {
                    z = true;
                }
            } else if (z) {
                sb.append(',');
                z = false;
            } else {
                arrayList.add(parseOption(sb.toString()));
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            arrayList.add(parseOption(sb.toString()));
        }
        return arrayList;
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r8v3, types: [java.lang.String] */
    /* JADX WARN: Type inference failed for: r8v6, types: [java.lang.Integer] */
    /* JADX WARN: Type inference failed for: r8v7, types: [java.lang.Long] */
    /* JADX WARN: Type inference failed for: r8v9, types: [java.lang.Float] */
    private static CodecOption parseOption(String str) {
        String strSubstring;
        String strSubstring2;
        int iIndexOf = str.indexOf(61);
        if (iIndexOf == -1) {
            throw new IllegalArgumentException("'=' expected");
        }
        String strSubstring3 = str.substring(0, iIndexOf);
        if (strSubstring3.length() == 0) {
            throw new IllegalArgumentException("Key may not be null");
        }
        int iIndexOf2 = strSubstring3.indexOf(58);
        if (iIndexOf2 == -1) {
            strSubstring = strSubstring3;
            strSubstring2 = "int";
        } else {
            strSubstring = strSubstring3.substring(0, iIndexOf2);
            strSubstring2 = strSubstring3.substring(iIndexOf2 + 1);
        }
        String strVal = str.substring(iIndexOf + 1);
        Object objSubstring = strVal;
        strSubstring2.hashCode();
        switch (strSubstring2) {
            case "string":
                break;
            case "int":
                objSubstring = Integer.valueOf(Integer.parseInt(strVal));
                break;
            case "long":
                objSubstring = Long.valueOf(Long.parseLong(strVal));
                break;
            case "float":
                objSubstring = Float.valueOf(Float.parseFloat(strVal));
                break;
            default:
                throw new IllegalArgumentException("Invalid codec option type (int, long, float, str): " + strSubstring2);
        }
        return new CodecOption(strSubstring, objSubstring);
    }
}
