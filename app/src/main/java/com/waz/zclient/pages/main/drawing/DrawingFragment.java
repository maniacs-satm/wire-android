/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.drawing;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import com.waz.api.ImageAsset;
import com.waz.api.ImageAssetFactory;
import com.waz.api.LoadHandle;
import com.waz.api.MemoryImageCache;
import com.waz.threading.Threading;
import com.waz.zclient.OnBackPressedListener;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.controllers.drawing.DrawingController;
import com.waz.zclient.controllers.userpreferences.IUserPreferencesController;
import com.waz.zclient.pages.BaseFragment;
import com.waz.zclient.ui.colorpicker.ColorPickerLayout;
import com.waz.zclient.ui.colorpicker.ColorPickerScrollView;
import com.waz.zclient.ui.colorpicker.EmojiBottomSheetDialog;
import com.waz.zclient.ui.colorpicker.EmojiSize;
import com.waz.zclient.ui.sketch.DrawingCanvasView;
import com.waz.zclient.ui.text.TypefaceTextView;
import com.waz.zclient.utils.Emojis;
import com.waz.zclient.utils.LayoutSpec;
import com.waz.zclient.utils.StringUtils;
import com.waz.zclient.utils.TrackingUtils;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.utils.debug.ShakeEventListener;
import net.hockeyapp.android.ExceptionHandler;

import java.util.Locale;

public class DrawingFragment extends BaseFragment<DrawingFragment.Container> implements OnBackPressedListener,
                                                                                        ColorPickerLayout.OnColorSelectedListener,
                                                                                        DrawingCanvasView.DrawingCanvasCallback,
                                                                                        ViewTreeObserver.OnScrollChangedListener,
                                                                                        AccentColorObserver,
                                                                                        ColorPickerLayout.OnWidthChangedListener {

    public static final String TAG = DrawingFragment.class.getName();
    private static final String SAVED_INSTANCE_BITMAP = "SAVED_INSTANCE_BITMAP";
    private static final String ARGUMENT_BACKGROUND_IMAGE = "ARGUMENT_BACKGROUND_IMAGE";
    private static final String ARGUMENT_DRAWING_DESTINATION = "ARGUMENT_DRAWING_DESTINATION";

    private ShakeEventListener shakeEventListener;
    private SensorManager sensorManager;

    private DrawingCanvasView drawingCanvasView;
    private ColorPickerLayout colorLayout;
    private HorizontalScrollView colorPickerScrollContainer;
    private TypefaceTextView conversationTitle;

    private ColorPickerScrollView colorPickerScrollBar;

    private TypefaceTextView drawingViewTip;
    private View drawingTipBackground;

    //normal sketch or single image view edit
    private TextView sendDrawingButton;
    private TextView cancelDrawingButton;
    private TextView undoSketchButton;

    private ImageAsset backgroundImage;
    private LoadHandle bitmapLoadHandle;

    private DrawingController.DrawingDestination drawingDestination;
    private boolean includeBackgroundImage;
    private EmojiSize currentEmojiSize = EmojiSize.SMALL;

    public static DrawingFragment newInstance(ImageAsset backgroundAsset, DrawingController.DrawingDestination drawingDestination) {
        DrawingFragment fragment = new DrawingFragment();
        Bundle bundle = new Bundle();
        bundle.putParcelable(ARGUMENT_BACKGROUND_IMAGE, backgroundAsset);
        bundle.putString(ARGUMENT_DRAWING_DESTINATION, drawingDestination.toString());
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LayoutSpec.isTablet(getActivity())) {
            ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, getActivity());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        backgroundImage = args.getParcelable(ARGUMENT_BACKGROUND_IMAGE);
        drawingDestination = DrawingController.DrawingDestination.valueOf(args.getString(ARGUMENT_DRAWING_DESTINATION));
        sensorManager = (SensorManager) getActivity().getSystemService(Activity.SENSOR_SERVICE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_drawing, container, false);
        drawingCanvasView = ViewUtils.getView(rootView, R.id.dcv__canvas);
        drawingCanvasView.setDrawingCanvasCallback(this);
        drawingCanvasView.setDrawingColor(getControllerFactory().getAccentColorController().getColor());
        drawingCanvasView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        drawingViewTip.setVisibility(View.GONE);
                        drawingTipBackground.setVisibility(View.GONE);
                        drawingCanvasView.setOnTouchListener(null);
                        break;
                }
                return false;
            }
        });

        colorPickerScrollContainer = ViewUtils.getView(rootView, R.id.hsv_color_picker_scroll_view);

        colorLayout = ViewUtils.getView(rootView, R.id.cpdl__color_layout);
        colorLayout.setOnColorSelectedListener(this);
        int[] colors = getResources().getIntArray(R.array.draw_color);
        colorLayout.setAccentColors(colors, getControllerFactory().getAccentColorController().getColor());
        colorLayout.getViewTreeObserver().addOnScrollChangedListener(this);

        colorPickerScrollBar = ViewUtils.getView(rootView, R.id.cpsb__color_picker_scrollbar);
        colorPickerScrollBar.setScrollBarColor(getControllerFactory().getAccentColorController().getColor());

        conversationTitle = ViewUtils.getView(rootView, R.id.ttv__drawing__conversation__name);
        conversationTitle.setText(getStoreFactory().getConversationStore().getCurrentConversation().getName().toUpperCase(Locale.getDefault()));

        drawingTipBackground = ViewUtils.getView(rootView, R.id.v__tip_background);
        drawingViewTip = ViewUtils.getView(rootView, R.id.ttv__drawing__view__tip);
        drawingTipBackground.setVisibility(View.INVISIBLE);

        cancelDrawingButton = ViewUtils.getView(rootView, R.id.tv__cancel_button);
        cancelDrawingButton.setOnClickListener(sketchButtonsOnClickListener);

        sendDrawingButton = ViewUtils.getView(rootView, R.id.tv__send_button);
        sendDrawingButton.setOnClickListener(sketchButtonsOnClickListener);
        sendDrawingButton.setClickable(false);

        undoSketchButton = ViewUtils.getView(rootView, R.id.tv__undo_button);
        undoSketchButton.setOnClickListener(sketchButtonsOnClickListener);
        undoSketchButton.setOnLongClickListener(sketchButtonsOnLongClickListener);
        undoSketchButton.setClickable(false);

        if (savedInstanceState != null) {
            Bitmap savedBitmap = savedInstanceState.getParcelable(SAVED_INSTANCE_BITMAP);
            if (savedBitmap != null) {
                // Use saved background image if exists
                drawingCanvasView.setBackgroundBitmap(savedBitmap);
            } else {
                setBackgroundBitmap();
            }
        } else {
            setBackgroundBitmap();
        }

        return rootView;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelable(SAVED_INSTANCE_BITMAP, getBitmapDrawing());
        super.onSaveInstanceState(outState);
    }

    public void setBackgroundBitmap() {
        if (getActivity() == null || backgroundImage == null) {
            return;
        }
        drawingViewTip.setText(getResources().getString(R.string.drawing__tip__picture__message));
        drawingTipBackground.setVisibility(View.VISIBLE);
        drawingViewTip.setTextColor(getResources().getColor(R.color.drawing__tip__font__color_image));
        cancelLoadHandle();
        bitmapLoadHandle = backgroundImage.getSingleBitmap(ViewUtils.getOrientationDependentDisplayWidth(getActivity()), new ImageAsset.BitmapCallback() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, boolean isPreview) {
                if (getActivity() == null || drawingCanvasView == null || isPreview) {
                    return;
                }
                includeBackgroundImage = true;
                drawingCanvasView.setBackgroundBitmap(bitmap);
                cancelLoadHandle();
            }

            @Override
            public void onBitmapLoadingFailed() {
                cancelLoadHandle();
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        colorLayout.setOnWidthChangedListener(this);
        getStoreFactory().getInAppNotificationStore().setUserSendingPicture(true);
        shakeEventListener = new ShakeEventListener();
        shakeEventListener.setOnShakeListener(new ShakeEventListener.OnShakeListener() {
            @Override
            public void onShake() {
                if (includeBackgroundImage) {
                    drawingCanvasView.removeBackgroundBitmap();
                    includeBackgroundImage = false;
                } else {
                    drawingCanvasView.drawBackgroundBitmap();
                    includeBackgroundImage = true;
                }
            }
        });
        sensorManager.registerListener(shakeEventListener,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_NORMAL);
        if (!getControllerFactory().getUserPreferencesController().hasCheckedForUnsupportedEmojis(Emojis.VERSION)) {
            Threading.Background().execute(new Runnable() {
                @Override
                public void run() {
                    checkForUnsupportedEmojis(Emojis.ACTIVITY,
                                              Emojis.FLAGS,
                                              Emojis.FOOD_AND_DRINK,
                                              Emojis.NATURE,
                                              Emojis.OBJECTS,
                                              Emojis.PEOPLE,
                                              Emojis.SYMBOLS,
                                              Emojis.TRAVEL_AND_PLACES);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (LayoutSpec.isTablet(getActivity())) {
            ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, getActivity());
        }
    }

    @Override
    public void onStop() {
        getStoreFactory().getInAppNotificationStore().setUserSendingPicture(false);
        sensorManager.unregisterListener(shakeEventListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (drawingCanvasView != null) {
            drawingCanvasView.onDestroy();
            drawingCanvasView = null;
        }
        colorLayout = null;
        sendDrawingButton = null;
        cancelDrawingButton = null;
        cancelLoadHandle();
        super.onDestroyView();
    }

    public void cancelLoadHandle() {
        if (bitmapLoadHandle != null) {
            bitmapLoadHandle.cancel();
            bitmapLoadHandle = null;
        }
    }

    private Bitmap getBitmapDrawing() {
        return drawingCanvasView.getBitmap();
    }

    @Override
    public boolean onBackPressed() {
        if (drawingCanvasView != null && drawingCanvasView.undo()) {
            return true;
        }
        getControllerFactory().getDrawingController().hideDrawing(drawingDestination, false);
        return true;
    }

    @Override
    public void onScrollWidthChanged(int width) {
        colorPickerScrollBar.setScrollBarSize(width);
    }

    private View.OnClickListener sketchButtonsOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (drawingCanvasView == null) {
                return;
            }

            switch (v.getId()) {
                case R.id.tv__send_button:
                    if (!drawingCanvasView.isEmpty()) {
                        getStoreFactory().getConversationStore().sendMessage(getFinalSketchImage());
                        TrackingUtils.onSentSketchMessage(getControllerFactory().getTrackingController(),
                                                          getStoreFactory().getConversationStore().getCurrentConversation(),
                                                          drawingDestination);

                        getControllerFactory().getDrawingController().hideDrawing(drawingDestination, true);
                    }
                    break;
                case R.id.tv__cancel_button:
                    getControllerFactory().getDrawingController().hideDrawing(drawingDestination, false);
                    break;
                case R.id.tv__undo_button:
                    drawingCanvasView.undo();
                    break;
                default:
                    //nothing
                    break;
            }
        }
    };

    private View.OnLongClickListener sketchButtonsOnLongClickListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            switch (v.getId()) {
                case R.id.tv__undo_button:
                    if (drawingCanvasView == null) {
                        return true;
                    }
                    drawingCanvasView.reset();
                    return true;
                default:
                    //nothing
                    return false;
            }
        }
    };

    private ImageAsset getFinalSketchImage() {
        Bitmap finalBitmap = getBitmapDrawing();
        try {
            int x;
            int y;
            int width;
            int height;
            if (drawingCanvasView.isBackgroundImageLandscape()) {
                x = 0;
                y = drawingCanvasView.getTopTrimValue(true);
                width = finalBitmap.getWidth();
                height = drawingCanvasView.getBottomTrimValue(true) - y;

                if (height < drawingCanvasView.getLandscapeBackgroundBitmapHeight()) {
                    y = drawingCanvasView.getBackgroundBitmapTop();
                    height = drawingCanvasView.getLandscapeBackgroundBitmapHeight();
                }
            } else {
                x = 0;
                y = drawingCanvasView.getTopTrimValue(false);
                width = finalBitmap.getWidth();
                height = drawingCanvasView.getBottomTrimValue(false) - y;
            }
            MemoryImageCache.reserveImageMemory(width, height);
            finalBitmap = Bitmap.createBitmap(finalBitmap, x, y, width, height);
        } catch (OutOfMemoryError outOfMemoryError) {
            ExceptionHandler.saveException(outOfMemoryError, null);
        }

        return ImageAssetFactory.getImageAsset(finalBitmap, ExifInterface.ORIENTATION_NORMAL);
    }

    @Override
    public void onScrollChanged() {
        if (colorPickerScrollBar == null || colorLayout == null) {
            return;
        }
        colorPickerScrollBar.setLeftX(colorPickerScrollContainer.getScrollX());
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        colorPickerScrollBar.setBackgroundColor(color);
    }

    @Override
    public void onColorSelected(int color, int strokeSize) {
        if (drawingCanvasView == null) {
            return;
        }
        drawingCanvasView.setDrawingColor(color);
        drawingCanvasView.setStrokeSize(strokeSize);
    }

    @Override
    public void onEmojiSelected(String emoji, int size) {
        drawingCanvasView.setEmoji(emoji, size);
    }

    @Override
    public void onMoreClick() {
        final EmojiBottomSheetDialog dialog = new EmojiBottomSheetDialog(getContext(),
                                                                         currentEmojiSize,
                                                                         new EmojiBottomSheetDialog.EmojiDialogListener() {
                                                                             @Override
                                                                             public void onEmojiSelected(String emoji, EmojiSize emojiSize) {
                                                                                 colorLayout.setCurrentEmoji(emoji, emojiSize);
                                                                                 currentEmojiSize = emojiSize;
                                                                                 getControllerFactory().getUserPreferencesController().addRecentEmoji(emoji);
                                                                             }
                                                                         },
                                                                         getControllerFactory().getUserPreferencesController().getRecentEmojis(),
                                                                         getControllerFactory().getUserPreferencesController().getUnsupportedEmojis());
        dialog.show();
    }

    @Override
    public void drawingAdded() {
        undoSketchButton.setTextColor(getResources().getColor(R.color.drawing__icon__enabled_color));
        undoSketchButton.setClickable(true);
        sendDrawingButton.setTextColor(getResources().getColor(R.color.drawing__icon__enabled_color));
        sendDrawingButton.setClickable(true);
    }

    @Override
    public void drawingCleared() {
        undoSketchButton.setTextColor(getResources().getColor(R.color.drawing__icon__disabled_color));
        undoSketchButton.setClickable(false);
        sendDrawingButton.setTextColor(getResources().getColor(R.color.drawing__icon__disabled_color));
        sendDrawingButton.setClickable(false);
    }

    @Override
    public void reserveBitmapMemory(int width, int height) {
        MemoryImageCache.reserveImageMemory(width, height);
    }

    private void checkForUnsupportedEmojis(String[]...arrays) {
        if (getControllerFactory() == null ||
            getControllerFactory().isTornDown()) {
            return;
        }
        IUserPreferencesController userPreferencesController = getControllerFactory().getUserPreferencesController();
        for (String[] array : arrays) {
            for (String emoji : array) {
                Paint paint = new Paint();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!paint.hasGlyph(emoji)) {
                        userPreferencesController.addUnsupportedEmoji(emoji);
                    }
                } else {
                    if (StringUtils.isCharacterMissingInFont(emoji)) {
                        userPreferencesController.addUnsupportedEmoji(emoji);
                    }
                }
            }
        }
        getControllerFactory().getUserPreferencesController().setCheckedForUnsupportedEmojis(Emojis.VERSION);
    }

    public interface Container { }
}
