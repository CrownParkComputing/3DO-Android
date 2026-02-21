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
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        GameLibraryActivity.GameItem item = games.get(position);
        
        // Set title
        holder.titleView.setText(item.igdbGame != null ? item.igdbGame.name : item.name);
        
        // Set cover
        if (item.coverBitmap != null) {
            holder.coverView.setImageBitmap(item.coverBitmap);
        } else {
            holder.coverView.setImageResource(R.mipmap.ic_launcher);
        }

        return convertView;
    }

    static class ViewHolder {
        ImageView coverView;
        TextView titleView;
    }
}