package com.astrodev.ocr_translate.util;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.Text.Line;
import com.google.mlkit.vision.text.Text.TextBlock;

/**
 * Graphic instance for rendering TextBlock position, size, and ID within an associated graphic
 * overlay view.
 */
public class TextGraphic extends GraphicOverlay.Graphic {

    private static final String TAG = "TextGraphic";
    private static final int TEXT_COLOR = Color.BLACK;
    private static final int MARKER_COLOR = Color.argb(150, 255, 255, 255);
    private static final float TEXT_SIZE = 54.0f;
    private static final float STROKE_WIDTH = 4.0f;
    private static Rect AUTO_SIZE = new Rect();
    private static Paint paint = new Paint();
    final float testTextSize = 55f;

    private final Paint rectPaint;
    private final Paint textPaint;
    private final Paint labelPaint;
    private final Text text;

    public TextGraphic(GraphicOverlay overlay, Text text) {
        super(overlay);

        this.text = text;

        rectPaint = new Paint();
        rectPaint.setColor(MARKER_COLOR);
        rectPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        rectPaint.setStrokeWidth(STROKE_WIDTH);

        textPaint = new Paint();
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(TEXT_SIZE);

        labelPaint = new Paint();
        labelPaint.setColor(MARKER_COLOR);
        labelPaint.setStyle(Paint.Style.FILL);
        // Redraw the overlay, as this graphic has been added.
        postInvalidate();
    }

    /**
     * Draws the text block annotations for position, size, and raw value on the supplied canvas.
     */
    @Override
    public void draw(Canvas canvas) {
        Log.d(TAG, "Text is: " + text.getText());

        for (TextBlock textBlock : text.getTextBlocks()) {

            for (Line line : textBlock.getLines()) {

                // Draws the bounding box around the TextBlock.
                RectF rect = new RectF(line.getBoundingBox());
                rect.left = translateX(rect.left);
                rect.top = translateY(rect.top);
                rect.right = translateX(rect.right);
                rect.bottom = translateY(rect.bottom);


                textPaint.setTextSize(adjustTextSize(line.getText(), Math.abs(rect.top - rect.bottom), Math.abs(rect.left - rect.right)));
                float yPos = Math.abs(AUTO_SIZE.height()) >> 1;

                float textWidth = textPaint.measureText(line.getText());
                float left = isImageFlipped() ? rect.right : rect.left;


                //   canvas.drawRect(rect, rectPaint);
                if (Math.abs(rect.left - rect.right) < Math.abs(((left - STROKE_WIDTH) - (left + textWidth + 2 * STROKE_WIDTH)))) {
                    canvas.drawRoundRect(
                            left - STROKE_WIDTH,
                            rect.top,
                            left + textWidth + 2 * STROKE_WIDTH,
                            rect.bottom, 20, 20,
                            labelPaint);

                } else {
                    canvas.drawRoundRect(
                            rect, 20, 20,
                            labelPaint);

                }
                // Renders the text at the bottom of the box.
                float textHeight = textPaint.descent() - textPaint.ascent();
                float textOffset = (textHeight / 2) - textPaint.descent();
                canvas.drawText(line.getText(), rect.left, (rect.centerY() + textOffset), textPaint);

                //Element extraction
               /*
                for (Element element : line.getElements()) {

                    Log.d(TAG, "Element text is: " + element.getText());
                    Log.d(TAG, "Element boundingbox is: " + element.getBoundingBox());
                    Log.d(TAG, "Element cornerpoint is: " + Arrays.toString(element.getCornerPoints()));
                    Log.d(TAG, "Element language is: " + element.getRecognizedLanguage());
                }

              */
            }
        }
    }

    private float adjustTextSize(String text, float desiredHeight, float desiredWidth) {

        paint.setTextSize(testTextSize);
        paint.getTextBounds(text, 0, text.length(), AUTO_SIZE);
        float size = testTextSize * Math.min(desiredWidth / AUTO_SIZE.width(), desiredHeight / AUTO_SIZE.height());

        return size;
    }
}