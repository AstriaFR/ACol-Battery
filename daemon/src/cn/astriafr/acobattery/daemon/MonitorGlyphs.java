package cn.astriafr.acobattery.daemon;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Roboto outlines (wght=700, wdth=75), scaled to 8.25 dp for the performance monitor.
 * Source: google/fonts, ofl/roboto/Roboto[wdth,wght].ttf, blob 5522a368d9072fd88c299916e61fcff369949061.
 * Glyph outlines: Copyright 2011 The Roboto Project Authors, SIL OFL 1.1.
 * License: src/main/assets/webroot/assets/acol-monitor-OFL.txt.
 */
final class MonitorGlyphs {
    static final float CAP_HEIGHT = 5.8652f;
    private static final String CHARACTERS = "0123456789.+−—%W°C";
    private static final Glyph[] GLYPHS = {
        zero(), one(), two(), three(), four(), five(), six(), seven(), eight(), nine(),
        point(), plus(), minus(), dash(), percent(), watt(), degree(), celsius()
    };

    private MonitorGlyphs() {}

    private static final class Glyph {
        final Path path;
        final float advance;

        Glyph(Path path, float advance) {
            this.path = path;
            this.advance = advance;
        }
    }

    private static Glyph glyph(char character) {
        int index = CHARACTERS.indexOf(character);
        return GLYPHS[index >= 0 ? index : CHARACTERS.indexOf('—')];
    }

    static float width(char character) {
        return glyph(character).advance;
    }

    static float width(String value) {
        float total = 0;
        for (int i = 0; i < value.length(); i++) total += width(value.charAt(i));
        return total;
    }

    static float draw(Canvas canvas, Paint paint, char character, float x, float baseline) {
        Glyph glyph = glyph(character);
        canvas.save();
        try {
            canvas.translate(x, baseline);
            canvas.drawPath(glyph.path, paint);
        } finally {
            canvas.restore();
        }
        return glyph.advance;
    }

    static float draw(Canvas canvas, Paint paint, String value, float x, float baseline) {
        float end = x;
        for (int i = 0; i < value.length(); i++) {
            end += draw(canvas, paint, value.charAt(i), end, baseline);
        }
        return end - x;
    }

    private static Glyph zero() {
        Path path = new Path();
        path.moveTo(3.7855f, -3.4463f);
        path.lineTo(3.7855f, -2.428f);
        path.quadTo(3.7855f, -1.767f, 3.6613f, -1.2926f);
        path.quadTo(3.537f, -0.8181f, 3.3123f, -0.5133f);
        path.quadTo(3.0875f, -0.2084f, 2.7772f, -0.0639f);
        path.quadTo(2.4668f, 0.0806f, 2.0902f, 0.0806f);
        path.quadTo(1.7918f, 0.0806f, 1.5312f, -0.0128f);
        path.quadTo(1.2705f, -0.1062f, 1.0584f, -0.297f);
        path.quadTo(0.8462f, -0.4878f, 0.6954f, -0.7845f);
        path.quadTo(0.5445f, -1.0812f, 0.4643f, -1.4897f);
        path.quadTo(0.3841f, -1.8983f, 0.3841f, -2.428f);
        path.lineTo(0.3841f, -3.4463f);
        path.quadTo(0.3841f, -4.1101f, 0.5095f, -4.5825f);
        path.quadTo(0.6349f, -5.0549f, 0.8605f, -5.3578f);
        path.quadTo(1.0862f, -5.6607f, 1.3994f, -5.8032f);
        path.quadTo(1.7126f, -5.9458f, 2.0856f, -5.9458f);
        path.quadTo(2.3819f, -5.9458f, 2.6442f, -5.855f);
        path.quadTo(2.9065f, -5.7643f, 3.1147f, -5.5741f);
        path.quadTo(3.323f, -5.3839f, 3.4736f, -5.0892f);
        path.quadTo(3.6242f, -4.7945f, 3.7048f, -4.3852f);
        path.quadTo(3.7855f, -3.976f, 3.7855f, -3.4463f);
        path.close();
        path.moveTo(2.6565f, -2.2732f);
        path.lineTo(2.6565f, -3.6118f);
        path.quadTo(2.6565f, -3.9223f, 2.6292f, -4.157f);
        path.quadTo(2.6019f, -4.3918f, 2.5569f, -4.553f);
        path.quadTo(2.5119f, -4.7142f, 2.4404f, -4.8146f);
        path.quadTo(2.3689f, -4.9149f, 2.2801f, -4.9617f);
        path.quadTo(2.1913f, -5.0085f, 2.0846f, -5.0085f);
        path.quadTo(1.9556f, -5.0085f, 1.8497f, -4.9356f);
        path.quadTo(1.7438f, -4.8627f, 1.6681f, -4.6995f);
        path.quadTo(1.5924f, -4.5364f, 1.5528f, -4.2688f);
        path.quadTo(1.5132f, -4.0012f, 1.5132f, -3.6118f);
        path.lineTo(1.5132f, -2.2732f);
        path.quadTo(1.5132f, -1.96f, 1.5406f, -1.7232f);
        path.quadTo(1.5679f, -1.4864f, 1.6172f, -1.3199f);
        path.quadTo(1.6664f, -1.1535f, 1.734f, -1.0525f);
        path.quadTo(1.8016f, -0.9515f, 1.892f, -0.9048f);
        path.quadTo(1.9824f, -0.858f, 2.0902f, -0.858f);
        path.quadTo(2.2203f, -0.858f, 2.3275f, -0.9335f);
        path.quadTo(2.4348f, -1.0089f, 2.5063f, -1.1728f);
        path.quadTo(2.5777f, -1.3366f, 2.6171f, -1.6095f);
        path.quadTo(2.6565f, -1.8824f, 2.6565f, -2.2732f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph one() {
        Path path = new Path();
        path.moveTo(2.8863f, -5.8756f);
        path.lineTo(2.8863f, 0f);
        path.lineTo(1.7545f, 0f);
        path.lineTo(1.7545f, -4.4912f);
        path.lineTo(0.6376f, -4.0616f);
        path.lineTo(0.6376f, -5.0048f);
        path.lineTo(2.7642f, -5.8756f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph two() {
        Path path = new Path();
        path.moveTo(3.8389f, -0.9386f);
        path.lineTo(3.8389f, 0f);
        path.lineTo(0.384f, 0f);
        path.lineTo(0.384f, -0.8008f);
        path.lineTo(1.9709f, -2.8216f);
        path.quadTo(2.198f, -3.1348f, 2.3284f, -3.376f);
        path.quadTo(2.4588f, -3.6172f, 2.5137f, -3.8096f);
        path.quadTo(2.5687f, -4.002f, 2.5687f, -4.1731f);
        path.quadTo(2.5687f, -4.4307f, 2.5087f, -4.6188f);
        path.quadTo(2.4486f, -4.8068f, 2.3294f, -4.9084f);
        path.quadTo(2.2103f, -5.01f, 2.0397f, -5.01f);
        path.quadTo(1.8283f, -5.01f, 1.6924f, -4.8813f);
        path.quadTo(1.5566f, -4.7527f, 1.4885f, -4.5297f);
        path.quadTo(1.4205f, -4.3066f, 1.4205f, -4.0241f);
        path.lineTo(0.2899f, -4.0241f);
        path.quadTo(0.2899f, -4.5496f, 0.5046f, -4.989f);
        path.quadTo(0.7192f, -5.4283f, 1.1139f, -5.6884f);
        path.quadTo(1.5085f, -5.9485f, 2.0576f, -5.9485f);
        path.quadTo(2.5966f, -5.9485f, 2.9621f, -5.7471f);
        path.quadTo(3.3275f, -5.5457f, 3.5154f, -5.1741f);
        path.quadTo(3.7033f, -4.8025f, 3.7033f, -4.2836f);
        path.quadTo(3.7033f, -3.993f, 3.626f, -3.7169f);
        path.quadTo(3.5487f, -3.4407f, 3.4073f, -3.1693f);
        path.quadTo(3.2659f, -2.8979f, 3.0596f, -2.6157f);
        path.quadTo(2.8533f, -2.3334f, 2.5991f, -2.0291f);
        path.lineTo(1.8318f, -0.9386f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph three() {
        Path path = new Path();
        path.moveTo(1.3982f, -3.4514f);
        path.lineTo(1.9487f, -3.4514f);
        path.quadTo(2.1586f, -3.4514f, 2.295f, -3.5511f);
        path.quadTo(2.4314f, -3.6508f, 2.4968f, -3.8338f);
        path.quadTo(2.5621f, -4.0169f, 2.5621f, -4.2601f);
        path.quadTo(2.5621f, -4.4793f, 2.5014f, -4.6475f);
        path.quadTo(2.4407f, -4.8157f, 2.3162f, -4.9129f);
        path.quadTo(2.1917f, -5.01f, 2.0019f, -5.01f);
        path.quadTo(1.8581f, -5.01f, 1.7315f, -4.9312f);
        path.quadTo(1.6049f, -4.8525f, 1.5307f, -4.7038f);
        path.quadTo(1.4565f, -4.555f, 1.4565f, -4.3508f);
        path.lineTo(0.326f, -4.3508f);
        path.quadTo(0.326f, -4.8334f, 0.5455f, -5.1931f);
        path.quadTo(0.7649f, -5.5529f, 1.1394f, -5.7507f);
        path.quadTo(1.5138f, -5.9485f, 1.9702f, -5.9485f);
        path.quadTo(2.4801f, -5.9485f, 2.8676f, -5.7605f);
        path.quadTo(3.2551f, -5.5724f, 3.4732f, -5.1991f);
        path.quadTo(3.6912f, -4.8258f, 3.6912f, -4.2763f);
        path.quadTo(3.6912f, -3.9994f, 3.5866f, -3.7373f);
        path.quadTo(3.4821f, -3.4752f, 3.2788f, -3.2666f);
        path.quadTo(3.0756f, -3.0581f, 2.7866f, -2.9374f);
        path.quadTo(2.4977f, -2.8168f, 2.1314f, -2.8168f);
        path.lineTo(1.3982f, -2.8168f);
        path.close();
        path.moveTo(1.3982f, -2.5374f);
        path.lineTo(1.3982f, -3.1559f);
        path.lineTo(2.1324f, -3.1559f);
        path.quadTo(2.5429f, -3.1559f, 2.851f, -3.0476f);
        path.quadTo(3.1591f, -2.9394f, 3.3627f, -2.7394f);
        path.quadTo(3.5664f, -2.5394f, 3.6658f, -2.261f);
        path.quadTo(3.7652f, -1.9826f, 3.7652f, -1.649f);
        path.quadTo(3.7652f, -1.2367f, 3.6286f, -0.9138f);
        path.quadTo(3.492f, -0.5909f, 3.2461f, -0.3693f);
        path.quadTo(3.0002f, -0.1477f, 2.6746f, -0.0336f);
        path.quadTo(2.349f, 0.0806f, 1.9658f, 0.0806f);
        path.quadTo(1.648f, 0.0806f, 1.3436f, -0.0194f);
        path.quadTo(1.0391f, -0.1194f, 0.7976f, -0.3288f);
        path.quadTo(0.556f, -0.5382f, 0.4152f, -0.8572f);
        path.quadTo(0.2744f, -1.1762f, 0.2744f, -1.6094f);
        path.lineTo(1.4021f, -1.6094f);
        path.quadTo(1.4021f, -1.393f, 1.4804f, -1.2225f);
        path.quadTo(1.5588f, -1.052f, 1.691f, -0.955f);
        path.quadTo(1.8231f, -0.858f, 1.9923f, -0.858f);
        path.quadTo(2.1943f, -0.858f, 2.3377f, -0.9585f);
        path.quadTo(2.4811f, -1.059f, 2.5572f, -1.2398f);
        path.quadTo(2.6333f, -1.4207f, 2.6333f, -1.6631f);
        path.quadTo(2.6333f, -1.9738f, 2.5528f, -2.166f);
        path.quadTo(2.4723f, -2.3581f, 2.3202f, -2.4477f);
        path.quadTo(2.1681f, -2.5374f, 1.9487f, -2.5374f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph four() {
        Path path = new Path();
        path.moveTo(3.887f, -2.2092f);
        path.lineTo(3.887f, -1.2706f);
        path.lineTo(0.3048f, -1.2706f);
        path.lineTo(0.2541f, -2.0059f);
        path.lineTo(2.2467f, -5.8652f);
        path.lineTo(3.1404f, -5.8652f);
        path.lineTo(2.2296f, -4.1477f);
        path.lineTo(1.3574f, -2.2092f);
        path.close();
        path.moveTo(3.3872f, -5.8652f);
        path.lineTo(3.3872f, 0f);
        path.lineTo(2.2554f, 0f);
        path.lineTo(2.2554f, -5.8652f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph five() {
        Path path = new Path();
        path.moveTo(1.404f, -2.6597f);
        path.lineTo(0.5046f, -2.885f);
        path.lineTo(0.799f, -5.8652f);
        path.lineTo(3.6291f, -5.8652f);
        path.lineTo(3.6291f, -4.8965f);
        path.lineTo(1.7254f, -4.8965f);
        path.lineTo(1.6145f, -3.6366f);
        path.quadTo(1.7078f, -3.7008f, 1.8903f, -3.778f);
        path.quadTo(2.0727f, -3.8553f, 2.2993f, -3.8553f);
        path.quadTo(2.6629f, -3.8553f, 2.9453f, -3.7218f);
        path.quadTo(3.2278f, -3.5882f, 3.4213f, -3.3345f);
        path.quadTo(3.6149f, -3.0808f, 3.7141f, -2.7155f);
        path.quadTo(3.8133f, -2.3502f, 3.8133f, -1.8867f);
        path.quadTo(3.8133f, -1.4938f, 3.7087f, -1.1406f);
        path.quadTo(3.6042f, -0.7875f, 3.3889f, -0.5127f);
        path.quadTo(3.1737f, -0.238f, 2.8516f, -0.0787f);
        path.quadTo(2.5296f, 0.0806f, 2.097f, 0.0806f);
        path.quadTo(1.7698f, 0.0806f, 1.4665f, -0.0331f);
        path.quadTo(1.1632f, -0.1468f, 0.9226f, -0.3686f);
        path.quadTo(0.682f, -0.5904f, 0.5365f, -0.9082f);
        path.quadTo(0.391f, -1.226f, 0.3888f, -1.6314f);
        path.lineTo(1.5064f, -1.6314f);
        path.quadTo(1.5243f, -1.392f, 1.5998f, -1.2187f);
        path.quadTo(1.6753f, -1.0455f, 1.8004f, -0.9517f);
        path.quadTo(1.9255f, -0.858f, 2.0895f, -0.858f);
        path.quadTo(2.2555f, -0.858f, 2.3696f, -0.9405f);
        path.quadTo(2.4836f, -1.023f, 2.5533f, -1.1724f);
        path.quadTo(2.6229f, -1.3218f, 2.6522f, -1.5213f);
        path.quadTo(2.6815f, -1.7208f, 2.6815f, -1.9508f);
        path.quadTo(2.6815f, -2.1839f, 2.6412f, -2.372f);
        path.quadTo(2.6009f, -2.5601f, 2.5171f, -2.6985f);
        path.quadTo(2.4333f, -2.837f, 2.2982f, -2.9085f);
        path.quadTo(2.1631f, -2.9801f, 1.9842f, -2.9801f);
        path.quadTo(1.7366f, -2.9801f, 1.6126f, -2.8774f);
        path.quadTo(1.4885f, -2.7747f, 1.404f, -2.6597f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph six() {
        Path path = new Path();
        path.moveTo(2.9495f, -5.9291f);
        path.lineTo(3.0977f, -5.9291f);
        path.lineTo(3.0977f, -4.9704f);
        path.lineTo(3.0438f, -4.9704f);
        path.quadTo(2.661f, -4.9704f, 2.3782f, -4.8389f);
        path.quadTo(2.0955f, -4.7075f, 1.9071f, -4.4614f);
        path.quadTo(1.7187f, -4.2152f, 1.6269f, -3.866f);
        path.quadTo(1.5351f, -3.5168f, 1.5351f, -3.0865f);
        path.lineTo(1.5351f, -2.1007f);
        path.quadTo(1.5351f, -1.7915f, 1.5776f, -1.5587f);
        path.quadTo(1.6201f, -1.3259f, 1.7032f, -1.1708f);
        path.quadTo(1.7864f, -1.0157f, 1.8966f, -0.9395f);
        path.quadTo(2.0068f, -0.8632f, 2.1382f, -0.8632f);
        path.quadTo(2.2754f, -0.8632f, 2.3882f, -0.9422f);
        path.quadTo(2.5009f, -1.0212f, 2.5823f, -1.1651f);
        path.quadTo(2.6637f, -1.3091f, 2.7065f, -1.5005f);
        path.quadTo(2.7492f, -1.692f, 2.7492f, -1.9248f);
        path.quadTo(2.7492f, -2.1581f, 2.7043f, -2.3537f);
        path.quadTo(2.6594f, -2.5492f, 2.5792f, -2.6909f);
        path.quadTo(2.499f, -2.8327f, 2.384f, -2.9115f);
        path.quadTo(2.2691f, -2.9903f, 2.1266f, -2.9903f);
        path.quadTo(1.9204f, -2.9903f, 1.7769f, -2.8677f);
        path.quadTo(1.6334f, -2.7452f, 1.5551f, -2.5524f);
        path.quadTo(1.4767f, -2.3596f, 1.4689f, -2.1524f);
        path.lineTo(1.133f, -2.3933f);
        path.quadTo(1.1497f, -2.6986f, 1.2477f, -2.9706f);
        path.quadTo(1.3458f, -3.2427f, 1.5186f, -3.4603f);
        path.quadTo(1.6915f, -3.678f, 1.9327f, -3.8014f);
        path.quadTo(2.1739f, -3.9247f, 2.4757f, -3.9247f);
        path.quadTo(2.8258f, -3.9247f, 3.0888f, -3.7677f);
        path.quadTo(3.3519f, -3.6107f, 3.5272f, -3.3362f);
        path.quadTo(3.7025f, -3.0616f, 3.7879f, -2.7021f);
        path.quadTo(3.8733f, -2.3427f, 3.8733f, -1.9326f);
        path.quadTo(3.8733f, -1.5067f, 3.7534f, -1.1401f);
        path.quadTo(3.6335f, -0.7734f, 3.4114f, -0.4995f);
        path.quadTo(3.1894f, -0.2256f, 2.8728f, -0.0725f);
        path.quadTo(2.5562f, 0.0806f, 2.1628f, 0.0806f);
        path.quadTo(1.7601f, 0.0806f, 1.4361f, -0.0883f);
        path.quadTo(1.112f, -0.2571f, 0.881f, -0.5704f);
        path.quadTo(0.65f, -0.8836f, 0.5279f, -1.3097f);
        path.quadTo(0.4059f, -1.7358f, 0.4059f, -2.2528f);
        path.lineTo(0.4059f, -2.7055f);
        path.quadTo(0.4059f, -3.4159f, 0.5718f, -4.0067f);
        path.quadTo(0.7378f, -4.5974f, 1.0615f, -5.0283f);
        path.quadTo(1.3853f, -5.4593f, 1.858f, -5.6942f);
        path.quadTo(2.3308f, -5.9291f, 2.9495f, -5.9291f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph seven() {
        Path path = new Path();
        path.moveTo(3.8193f, -5.8652f);
        path.lineTo(3.8193f, -5.2192f);
        path.lineTo(1.966f, 0f);
        path.lineTo(0.7734f, 0f);
        path.lineTo(2.636f, -4.9267f);
        path.lineTo(0.2755f, -4.9267f);
        path.lineTo(0.2755f, -5.8652f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph eight() {
        Path path = new Path();
        path.moveTo(3.7666f, -1.6105f);
        path.quadTo(3.7666f, -1.0541f, 3.5511f, -0.6774f);
        path.quadTo(3.3355f, -0.3007f, 2.9548f, -0.1101f);
        path.quadTo(2.5741f, 0.0806f, 2.09f, 0.0806f);
        path.quadTo(1.6088f, 0.0806f, 1.2259f, -0.1101f);
        path.quadTo(0.8431f, -0.3007f, 0.621f, -0.6774f);
        path.quadTo(0.3988f, -1.0541f, 0.3988f, -1.611f);
        path.quadTo(0.3988f, -1.9864f, 0.5233f, -2.2847f);
        path.quadTo(0.6478f, -2.583f, 0.8764f, -2.7997f);
        path.quadTo(1.105f, -3.0165f, 1.4128f, -3.1313f);
        path.quadTo(1.7206f, -3.246f, 2.0821f, -3.246f);
        path.quadTo(2.5684f, -3.246f, 2.951f, -3.044f);
        path.quadTo(3.3337f, -2.842f, 3.5501f, -2.4767f);
        path.quadTo(3.7666f, -2.1114f, 3.7666f, -1.6105f);
        path.close();
        path.moveTo(2.6361f, -1.6941f);
        path.quadTo(2.6361f, -1.9564f, 2.5691f, -2.1458f);
        path.quadTo(2.5021f, -2.3352f, 2.3782f, -2.4367f);
        path.quadTo(2.2544f, -2.5383f, 2.0821f, -2.5383f);
        path.quadTo(1.9118f, -2.5383f, 1.7892f, -2.4367f);
        path.quadTo(1.6666f, -2.3352f, 1.598f, -2.1458f);
        path.quadTo(1.5293f, -1.9564f, 1.5293f, -1.6941f);
        path.quadTo(1.5293f, -1.4335f, 1.5967f, -1.2481f);
        path.quadTo(1.6641f, -1.0626f, 1.7899f, -0.9603f);
        path.quadTo(1.9157f, -0.858f, 2.0921f, -0.858f);
        path.quadTo(2.2653f, -0.858f, 2.3874f, -0.9603f);
        path.quadTo(2.5096f, -1.0626f, 2.5728f, -1.2481f);
        path.quadTo(2.6361f, -1.4335f, 2.6361f, -1.6941f);
        path.close();
        path.moveTo(3.6688f, -4.3054f);
        path.quadTo(3.6688f, -3.8545f, 3.4671f, -3.512f);
        path.quadTo(3.2653f, -3.1695f, 2.9086f, -2.9769f);
        path.quadTo(2.5518f, -2.7843f, 2.0893f, -2.7843f);
        path.quadTo(1.625f, -2.7843f, 1.2653f, -2.9769f);
        path.quadTo(0.9056f, -3.1695f, 0.7019f, -3.512f);
        path.quadTo(0.4982f, -3.8545f, 0.4982f, -4.3054f);
        path.quadTo(0.4982f, -4.8385f, 0.7019f, -5.2042f);
        path.quadTo(0.9056f, -5.5699f, 1.2644f, -5.7592f);
        path.quadTo(1.6232f, -5.9485f, 2.0882f, -5.9485f);
        path.quadTo(2.5515f, -5.9485f, 2.9102f, -5.7592f);
        path.quadTo(3.2688f, -5.5699f, 3.4688f, -5.2042f);
        path.quadTo(3.6688f, -4.8385f, 3.6688f, -4.3054f);
        path.close();
        path.moveTo(2.5409f, -4.2503f);
        path.quadTo(2.5409f, -4.4802f, 2.4916f, -4.65f);
        path.quadTo(2.4422f, -4.8198f, 2.3413f, -4.9149f);
        path.quadTo(2.2403f, -5.01f, 2.0873f, -5.01f);
        path.quadTo(1.9397f, -5.01f, 1.836f, -4.9197f);
        path.quadTo(1.7322f, -4.8294f, 1.6811f, -4.659f);
        path.quadTo(1.63f, -4.4886f, 1.63f, -4.2503f);
        path.quadTo(1.63f, -4.0188f, 1.6807f, -3.8447f);
        path.quadTo(1.7314f, -3.6706f, 1.8359f, -3.5731f);
        path.quadTo(1.9404f, -3.4756f, 2.0906f, -3.4756f);
        path.quadTo(2.2435f, -3.4756f, 2.3419f, -3.5739f);
        path.quadTo(2.4403f, -3.6723f, 2.4906f, -3.8456f);
        path.quadTo(2.5409f, -4.0189f, 2.5409f, -4.2503f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph nine() {
        Path path = new Path();
        path.moveTo(1.1104f, -0.9152f);
        path.lineTo(1.1695f, -0.9152f);
        path.quadTo(1.5535f, -0.9152f, 1.8292f, -1.0278f);
        path.quadTo(2.105f, -1.1404f, 2.2803f, -1.3601f);
        path.quadTo(2.4557f, -1.5798f, 2.5369f, -1.8993f);
        path.quadTo(2.618f, -2.2189f, 2.618f, -2.6354f);
        path.lineTo(2.618f, -3.7663f);
        path.quadTo(2.618f, -4.082f, 2.5741f, -4.3128f);
        path.quadTo(2.5302f, -4.5435f, 2.4504f, -4.698f);
        path.quadTo(2.3706f, -4.8524f, 2.2686f, -4.9305f);
        path.quadTo(2.1666f, -5.0087f, 2.0491f, -5.0087f);
        path.quadTo(1.9131f, -5.0087f, 1.8087f, -4.9141f);
        path.quadTo(1.7042f, -4.8196f, 1.6329f, -4.6614f);
        path.quadTo(1.5616f, -4.5033f, 1.5263f, -4.3017f);
        path.quadTo(1.491f, -4.1002f, 1.491f, -3.8849f);
        path.quadTo(1.491f, -3.6637f, 1.527f, -3.4642f);
        path.quadTo(1.5631f, -3.2647f, 1.6352f, -3.1136f);
        path.quadTo(1.7073f, -2.9625f, 1.8214f, -2.8775f);
        path.quadTo(1.9356f, -2.7926f, 2.0918f, -2.7926f);
        path.quadTo(2.2214f, -2.7926f, 2.3293f, -2.8675f);
        path.quadTo(2.4372f, -2.9423f, 2.5202f, -3.0647f);
        path.quadTo(2.6031f, -3.1871f, 2.6489f, -3.3387f);
        path.quadTo(2.6946f, -3.4903f, 2.701f, -3.6437f);
        path.lineTo(3.0665f, -3.4178f);
        path.quadTo(3.0665f, -3.1431f, 2.9674f, -2.8733f);
        path.quadTo(2.8682f, -2.6034f, 2.6959f, -2.3818f);
        path.quadTo(2.5236f, -2.1603f, 2.2951f, -2.029f);
        path.quadTo(2.0667f, -1.8978f, 1.8056f, -1.8978f);
        path.quadTo(1.4556f, -1.8978f, 1.1844f, -2.0479f);
        path.quadTo(0.9132f, -2.198f, 0.731f, -2.4643f);
        path.quadTo(0.5488f, -2.7307f, 0.4566f, -3.0901f);
        path.quadTo(0.3644f, -3.4494f, 0.3644f, -3.872f);
        path.quadTo(0.3644f, -4.2991f, 0.4829f, -4.6758f);
        path.quadTo(0.6014f, -5.0525f, 0.8228f, -5.338f);
        path.quadTo(1.0441f, -5.6234f, 1.3574f, -5.786f);
        path.quadTo(1.6706f, -5.9485f, 2.0573f, -5.9485f);
        path.quadTo(2.4416f, -5.9485f, 2.7507f, -5.7797f);
        path.quadTo(3.0598f, -5.6109f, 3.2828f, -5.2943f);
        path.quadTo(3.5057f, -4.9778f, 3.6245f, -4.5365f);
        path.quadTo(3.7434f, -4.0952f, 3.7434f, -3.5528f);
        path.lineTo(3.7434f, -3.1492f);
        path.quadTo(3.7434f, -2.5715f, 3.6429f, -2.077f);
        path.quadTo(3.5423f, -1.5825f, 3.3368f, -1.1889f);
        path.quadTo(3.1313f, -0.7952f, 2.8249f, -0.5154f);
        path.quadTo(2.5186f, -0.2356f, 2.1094f, -0.0872f);
        path.quadTo(1.7003f, 0.0611f, 1.1859f, 0.0611f);
        path.lineTo(1.1105f, 0.0611f);
        path.close();
        return new Glyph(path, 4.1709f);
    }

    private static Glyph point() {
        Path path = new Path();
        path.moveTo(0.5103f, -0.5743f);
        path.quadTo(0.5103f, -0.8436f, 0.6886f, -1.0222f);
        path.quadTo(0.8669f, -1.2007f, 1.1674f, -1.2007f);
        path.quadTo(1.4706f, -1.2007f, 1.6475f, -1.0222f);
        path.quadTo(1.8244f, -0.8436f, 1.8244f, -0.5761f);
        path.quadTo(1.8244f, -0.3101f, 1.6475f, -0.1323f);
        path.quadTo(1.4706f, 0.0456f, 1.1674f, 0.0456f);
        path.quadTo(0.8669f, 0.0456f, 0.6886f, -0.1329f);
        path.quadTo(0.5103f, -0.3114f, 0.5103f, -0.5743f);
        path.close();
        return new Glyph(path, 2.3976f);
    }

    private static Glyph plus() {
        Path path = new Path();
        path.moveTo(3.6401f, -3.2886f);
        path.lineTo(3.6401f, -2.2384f);
        path.lineTo(0.2643f, -2.2384f);
        path.lineTo(0.2643f, -3.2886f);
        path.close();
        path.moveTo(2.4949f, -4.8582f);
        path.lineTo(2.4949f, -0.5881f);
        path.lineTo(1.427f, -0.5881f);
        path.lineTo(1.427f, -4.8582f);
        path.close();
        return new Glyph(path, 3.9231f);
    }

    private static Glyph minus() {
        Path path = new Path();
        path.moveTo(3.5066f, -3.353f);
        path.lineTo(3.5066f, -2.4035f);
        path.lineTo(0.5664f, -2.4035f);
        path.lineTo(0.5664f, -3.353f);
        path.close();
        return new Glyph(path, 3.9937f);
    }

    private static Glyph dash() {
        Path path = new Path();
        path.moveTo(5.2009f, -3.353f);
        path.lineTo(5.2009f, -2.4035f);
        path.lineTo(0.49f, -2.4035f);
        path.lineTo(0.49f, -3.353f);
        path.close();
        return new Glyph(path, 5.4488f);
    }

    private static Glyph percent() {
        Path path = new Path();
        path.moveTo(0.3928f, -4.4271f);
        path.lineTo(0.3928f, -4.7373f);
        path.quadTo(0.3928f, -5.0717f, 0.516f, -5.3456f);
        path.quadTo(0.6392f, -5.6195f, 0.8841f, -5.7847f);
        path.quadTo(1.129f, -5.9498f, 1.4864f, -5.9498f);
        path.quadTo(1.8556f, -5.9498f, 2.1015f, -5.7847f);
        path.quadTo(2.3475f, -5.6195f, 2.467f, -5.3456f);
        path.quadTo(2.5865f, -5.0717f, 2.5865f, -4.7373f);
        path.lineTo(2.5865f, -4.4271f);
        path.quadTo(2.5865f, -4.1008f, 2.4656f, -3.8269f);
        path.quadTo(2.3448f, -3.553f, 2.101f, -3.3878f);
        path.quadTo(1.8573f, -3.2227f, 1.4932f, -3.2227f);
        path.quadTo(1.133f, -3.2227f, 0.8876f, -3.3878f);
        path.quadTo(0.6422f, -3.553f, 0.5175f, -3.8269f);
        path.quadTo(0.3928f, -4.1008f, 0.3928f, -4.4271f);
        path.close();
        path.moveTo(1.1306f, -4.7373f);
        path.lineTo(1.1306f, -4.4271f);
        path.quadTo(1.1306f, -4.2892f, 1.1719f, -4.1668f);
        path.quadTo(1.2132f, -4.0443f, 1.2922f, -3.9696f);
        path.quadTo(1.3712f, -3.895f, 1.4913f, -3.895f);
        path.quadTo(1.6154f, -3.895f, 1.6937f, -3.9692f);
        path.quadTo(1.772f, -4.0434f, 1.809f, -4.1662f);
        path.quadTo(1.8459f, -4.2889f, 1.8459f, -4.4271f);
        path.lineTo(1.8459f, -4.7373f);
        path.quadTo(1.8459f, -4.8778f, 1.8082f, -5.0033f);
        path.quadTo(1.7704f, -5.1288f, 1.6898f, -5.2032f);
        path.quadTo(1.6092f, -5.2775f, 1.4856f, -5.2775f);
        path.quadTo(1.3672f, -5.2775f, 1.288f, -5.2032f);
        path.quadTo(1.2087f, -5.1288f, 1.1697f, -5.0033f);
        path.quadTo(1.1306f, -4.8778f, 1.1306f, -4.7373f);
        path.close();
        path.moveTo(2.7084f, -1.1257f);
        path.lineTo(2.7084f, -1.439f);
        path.quadTo(2.7084f, -1.7684f, 2.8346f, -2.0424f);
        path.quadTo(2.9608f, -2.3163f, 3.205f, -2.4814f);
        path.quadTo(3.4492f, -2.6466f, 3.8046f, -2.6466f);
        path.quadTo(4.1756f, -2.6466f, 4.4187f, -2.4814f);
        path.quadTo(4.6618f, -2.3163f, 4.7833f, -2.0427f);
        path.quadTo(4.9048f, -1.7692f, 4.9048f, -1.4393f);
        path.lineTo(4.9048f, -1.1255f);
        path.quadTo(4.9048f, -0.7956f, 4.7844f, -0.522f);
        path.quadTo(4.6641f, -0.2485f, 4.4208f, -0.0833f);
        path.quadTo(4.1774f, 0.0819f, 3.8147f, 0.0819f);
        path.quadTo(3.4545f, 0.0819f, 3.2063f, -0.0833f);
        path.quadTo(2.9581f, -0.2485f, 2.8332f, -0.5224f);
        path.quadTo(2.7084f, -0.7963f, 2.7084f, -1.1257f);
        path.close();
        path.moveTo(3.4489f, -1.4381f);
        path.lineTo(3.4489f, -1.1266f);
        path.quadTo(3.4489f, -0.9901f, 3.4965f, -0.8665f);
        path.quadTo(3.5441f, -0.7428f, 3.6268f, -0.6666f);
        path.quadTo(3.7094f, -0.5904f, 3.8129f, -0.5904f);
        path.quadTo(3.9601f, -0.5904f, 4.0357f, -0.6666f);
        path.quadTo(4.1113f, -0.7428f, 4.1378f, -0.8659f);
        path.quadTo(4.1643f, -0.9889f, 4.1643f, -1.1266f);
        path.lineTo(4.1643f, -1.4381f);
        path.quadTo(4.1643f, -1.5798f, 4.1268f, -1.7028f);
        path.quadTo(4.0894f, -1.8259f, 4.0112f, -1.9001f);
        path.quadTo(3.9331f, -1.9743f, 3.8041f, -1.9743f);
        path.quadTo(3.6879f, -1.9743f, 3.6065f, -1.8995f);
        path.quadTo(3.5252f, -1.8247f, 3.4871f, -1.702f);
        path.quadTo(3.4489f, -1.5793f, 3.4489f, -1.4381f);
        path.close();
        path.moveTo(4.1063f, -5.022f);
        path.lineTo(1.7014f, -0.4378f);
        path.lineTo(1.162f, -0.747f);
        path.lineTo(3.567f, -5.3312f);
        path.close();
        return new Glyph(path, 5.2662f);
    }

    private static Glyph watt() {
        Path path = new Path();
        path.moveTo(1.671f, -0.8767f);
        path.lineTo(2.6084f, -5.8652f);
        path.lineTo(3.2301f, -5.8652f);
        path.lineTo(3.418f, -5.091f);
        path.lineTo(2.4082f, 0f);
        path.lineTo(1.7346f, 0f);
        path.close();
        path.moveTo(1.3504f, -5.8652f);
        path.lineTo(2.1088f, -0.8675f);
        path.lineTo(1.9778f, 0f);
        path.lineTo(1.2355f, 0f);
        path.lineTo(0.1835f, -5.8652f);
        path.close();
        path.moveTo(4.0962f, -0.8879f);
        path.lineTo(4.8506f, -5.8652f);
        path.lineTo(6.0121f, -5.8652f);
        path.lineTo(4.9652f, 0f);
        path.lineTo(4.2218f, 0f);
        path.close();
        path.moveTo(3.5907f, -5.8652f);
        path.lineTo(4.5369f, -0.8509f);
        path.lineTo(4.4609f, 0f);
        path.lineTo(3.7873f, 0f);
        path.lineTo(2.7845f, -5.1004f);
        path.lineTo(2.9743f, -5.8652f);
        path.close();
        return new Glyph(path, 6.1832f);
    }

    private static Glyph degree() {
        Path path = new Path();
        path.moveTo(0.5101f, -4.8372f);
        path.quadTo(0.5101f, -5.1396f, 0.6593f, -5.3916f);
        path.quadTo(0.8085f, -5.6435f, 1.0583f, -5.7953f);
        path.quadTo(1.3082f, -5.9471f, 1.6054f, -5.9471f);
        path.quadTo(1.9026f, -5.9471f, 2.1492f, -5.7953f);
        path.quadTo(2.3958f, -5.6435f, 2.5404f, -5.3913f);
        path.quadTo(2.6849f, -5.1391f, 2.6849f, -4.8356f);
        path.quadTo(2.6849f, -4.5322f, 2.5407f, -4.2845f);
        path.quadTo(2.3964f, -4.0368f, 2.15f, -3.8888f);
        path.quadTo(1.9036f, -3.7407f, 1.6053f, -3.7407f);
        path.quadTo(1.3085f, -3.7407f, 1.0585f, -3.8885f);
        path.quadTo(0.8085f, -4.0363f, 0.6593f, -4.2855f);
        path.quadTo(0.5101f, -4.5347f, 0.5101f, -4.8372f);
        path.close();
        path.moveTo(1.1033f, -4.8362f);
        path.quadTo(1.1033f, -4.6227f, 1.2522f, -4.4796f);
        path.quadTo(1.4011f, -4.3364f, 1.6056f, -4.3364f);
        path.quadTo(1.8133f, -4.3364f, 1.9551f, -4.4777f);
        path.quadTo(2.0969f, -4.6191f, 2.0969f, -4.8346f);
        path.quadTo(2.0969f, -5.0557f, 1.9561f, -5.2036f);
        path.quadTo(1.8154f, -5.3514f, 1.6072f, -5.3514f);
        path.quadTo(1.4002f, -5.3514f, 1.2518f, -5.2041f);
        path.quadTo(1.1033f, -5.0567f, 1.1033f, -4.8362f);
        path.close();
        return new Glyph(path, 3.2075f);
    }

    private static Glyph celsius() {
        Path path = new Path();
        path.moveTo(3.2716f, -1.9549f);
        path.lineTo(4.444f, -1.9549f);
        path.quadTo(4.418f, -1.2927f, 4.1764f, -0.8405f);
        path.quadTo(3.9348f, -0.3882f, 3.4895f, -0.1538f);
        path.quadTo(3.0442f, 0.0806f, 2.4183f, 0.0806f);
        path.quadTo(1.9301f, 0.0806f, 1.5503f, -0.0836f);
        path.quadTo(1.1704f, -0.2477f, 0.9064f, -0.5658f);
        path.quadTo(0.6423f, -0.8839f, 0.5067f, -1.3529f);
        path.quadTo(0.371f, -1.8219f, 0.371f, -2.4318f);
        path.lineTo(0.371f, -3.4296f);
        path.quadTo(0.371f, -4.0394f, 0.5141f, -4.5117f);
        path.quadTo(0.6573f, -4.984f, 0.924f, -5.3041f);
        path.quadTo(1.1907f, -5.6242f, 1.5729f, -5.7864f);
        path.quadTo(1.9552f, -5.9485f, 2.4346f, -5.9485f);
        path.quadTo(3.0749f, -5.9485f, 3.5062f, -5.7085f);
        path.quadTo(3.9376f, -5.4684f, 4.1724f, -5.0068f);
        path.quadTo(4.4072f, -4.5452f, 4.4534f, -3.8779f);
        path.lineTo(3.277f, -3.8779f);
        path.quadTo(3.2659f, -4.2983f, 3.1838f, -4.5373f);
        path.quadTo(3.1017f, -4.7763f, 2.9241f, -4.8747f);
        path.quadTo(2.7465f, -4.973f, 2.4346f, -4.973f);
        path.quadTo(2.1997f, -4.973f, 2.0297f, -4.8914f);
        path.quadTo(1.8597f, -4.8098f, 1.7525f, -4.6275f);
        path.quadTo(1.6452f, -4.4453f, 1.5947f, -4.1529f);
        path.quadTo(1.5441f, -3.8605f, 1.5441f, -3.4377f);
        path.lineTo(1.5441f, -2.4318f);
        path.quadTo(1.5441f, -2.0165f, 1.5873f, -1.7248f);
        path.quadTo(1.6305f, -1.433f, 1.7285f, -1.2488f);
        path.quadTo(1.8265f, -1.0647f, 1.9934f, -0.9791f);
        path.quadTo(2.1603f, -0.8936f, 2.4183f, -0.8936f);
        path.quadTo(2.7157f, -0.8936f, 2.8983f, -0.9818f);
        path.quadTo(3.0809f, -1.0701f, 3.1694f, -1.3002f);
        path.quadTo(3.2578f, -1.5304f, 3.2716f, -1.9549f);
        path.close();
        return new Glyph(path, 4.703f);
    }
}
