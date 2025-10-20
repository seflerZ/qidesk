package com.qihua.bVNC.gesture;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.qihua.bVNC.R;

import java.util.List;

public class GestureListAdapter extends RecyclerView.Adapter<GestureListAdapter.ViewHolder> {
    private final List<GestureHolder> gestureList;
    private GestureEditorActions gestureEditorActions;
    private Context context;

    public GestureListAdapter(List<GestureHolder> gestureList) {
        this.gestureList = gestureList;
    }

    public void addGesture(GestureHolder gestureHolder) {
        this.gestureList.add(gestureHolder);
    }

    public void removeGesture(int index) {
        gestureList.remove(index);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.gesture_list_item, parent, false);
        context = parent.getContext();
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(gestureList.get(position));
        holder.itemView.findViewById(R.id.itemGestureDelete).setOnClickListener(v -> {
            gestureEditorActions = (GestureEditorActions) context;
            gestureEditorActions.onDeleteClicked(gestureList.get(position), position);
        });
    }

    @Override
    public int getItemCount() {
        return gestureList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final View itemView;

        public ViewHolder(View itemView) {
            super(itemView);
            this.itemView = itemView;
        }

        public void bind(GestureHolder gestureItem) {
            ((android.widget.ImageView) itemView.findViewById(R.id.itemImgGesture))
                    .setImageBitmap(gestureItem.getGesture().toBitmap(60, 60, 3
                            , ContextCompat.getColor(itemView.getContext(), R.color.theme)));
            ((android.widget.TextView) itemView.findViewById(R.id.itemGestureName))
                    .setText(gestureItem.getName());
            ((android.widget.TextView) itemView.findViewById(R.id.itemGestureKeys))
                    .setText(gestureItem.getJoinedKeys());
        }
    }
}