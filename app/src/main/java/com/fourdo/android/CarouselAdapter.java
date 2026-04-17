package com.fourdo.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CarouselAdapter extends RecyclerView.Adapter<CarouselAdapter.CarouselViewHolder> {

    private Context context;
    private List<GameLibraryActivity.GameItem> gameItems;
    private OnGameClickListener clickListener;

    public interface OnGameClickListener {
        void onGameClick(GameLibraryActivity.GameItem item, int position);
    }

    public CarouselAdapter(Context context, List<GameLibraryActivity.GameItem> gameItems) {
        this.context = context;
        this.gameItems = gameItems;
        if (context instanceof OnGameClickListener) {
            this.clickListener = (OnGameClickListener) context;
        }
    }

    @NonNull
    @Override
    public CarouselViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.carousel_item, parent, false);
        return new CarouselViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CarouselViewHolder holder, int position) {
        GameLibraryActivity.GameItem item = gameItems.get(position);
        
        // Set game title
        holder.titleText.setText(item.igdbGame != null ? item.igdbGame.name : item.name);
        
        // Set cover image
        if (item.coverBitmap != null) {
            holder.coverImage.setImageBitmap(item.coverBitmap);
        } else {
            holder.coverImage.setImageResource(R.drawable.ic_launcher_foreground_source);
        }
        
        // Set year
        if (item.igdbGame != null && item.igdbGame.releaseDate != null && !item.igdbGame.releaseDate.isEmpty()) {
            holder.yearText.setText(item.igdbGame.releaseDate);
            holder.yearText.setVisibility(View.VISIBLE);
        } else {
            holder.yearText.setVisibility(View.GONE);
        }
        
        // Set publisher
        if (item.igdbGame != null && item.igdbGame.publisher != null && !item.igdbGame.publisher.isEmpty()) {
            holder.publisherText.setText(item.igdbGame.publisher);
            holder.publisherText.setVisibility(View.VISIBLE);
        } else {
            holder.publisherText.setVisibility(View.GONE);
        }
        
        // Click listener
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onGameClick(item, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return gameItems.size();
    }

    static class CarouselViewHolder extends RecyclerView.ViewHolder {
        ImageView coverImage;
        TextView titleText;
        TextView yearText;
        TextView publisherText;

        CarouselViewHolder(@NonNull View itemView) {
            super(itemView);
            coverImage = itemView.findViewById(R.id.carousel_cover);
            titleText = itemView.findViewById(R.id.carousel_title);
            yearText = itemView.findViewById(R.id.carousel_year);
            publisherText = itemView.findViewById(R.id.carousel_publisher);
        }
    }
}