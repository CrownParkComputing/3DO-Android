package com.fourdo.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

public class GameGridAdapter extends BaseAdapter {

    private final Context context;
    private final List<GameLibraryActivity.GameItem> games;
    private final LayoutInflater inflater;

    public GameGridAdapter(Context context, List<GameLibraryActivity.GameItem> games) {
        this.context = context;
        this.games = games;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return games.size();
    }

    @Override
    public Object getItem(int position) {
        return games.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.game_grid_item, parent, false);
            holder = new ViewHolder();
            holder.coverView = convertView.findViewById(R.id.game_cover);
            holder.titleView = convertView.findViewById(R.id.game_title);
            holder.detailView = convertView.findViewById(R.id.game_detail);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        GameLibraryActivity.GameItem item = games.get(position);
        
        // Set title
        holder.titleView.setText(item.igdbGame != null ? item.igdbGame.name : item.name);
        if (holder.detailView != null) {
            holder.detailView.setText(gameCardDetail(item));
        }
        
        // Set cover
        if (item.coverBitmap != null) {
            holder.coverView.setImageBitmap(item.coverBitmap);
        } else {
            holder.coverView.setImageResource(R.drawable.ic_launcher_foreground_source);
        }

        return convertView;
    }

    static class ViewHolder {
        ImageView coverView;
        TextView titleView;
        TextView detailView;
    }

    private String gameCardDetail(GameLibraryActivity.GameItem item) {
        if (item.igdbGame != null) {
            StringBuilder detail = new StringBuilder();
            if (item.igdbGame.releaseDate != null && !item.igdbGame.releaseDate.isEmpty()) {
                detail.append(item.igdbGame.releaseDate);
            }
            if (item.igdbGame.publisher != null && !item.igdbGame.publisher.isEmpty()) {
                if (detail.length() > 0) {
                    detail.append(" | ");
                }
                detail.append(item.igdbGame.publisher);
            }
            if (detail.length() > 0) {
                return detail.toString();
            }
        }

        if (item.filePath == null || item.filePath.isEmpty()) {
            return "3DO";
        }
        int dot = item.filePath.lastIndexOf('.');
        if (dot < 0 || dot == item.filePath.length() - 1) {
            return "3DO";
        }
        return item.filePath.substring(dot + 1).toUpperCase();
    }
}
