package net.basov.retrace;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import net.basov.retrace.core.TimeText;

// ----------------------------------------------------------------------
// The rows of the list of tracks
// ----------------------------------------------------------------------
//
// Each row uses the two-line layout of the framework. The first line is the
// name. The second line is the profile, the distance and the duration.
final class TrackListAdapter extends BaseAdapter {

    private final Context context;
    private final LayoutInflater inflater;
    private List<TrackStore.Entry> entries = new ArrayList<>();

    TrackListAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    void setEntries(List<TrackStore.Entry> newEntries) {
        entries = newEntries;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public TrackStore.Entry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView != null ? convertView
                : inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
        TrackStore.Entry e = entries.get(position);
        TextView first = row.findViewById(android.R.id.text1);
        TextView second = row.findViewById(android.R.id.text2);
        first.setText(e.name);
        if (e.readable) {
            second.setText(context.getString(R.string.track_details,
                    Labels.profile(context, e.profile),
                    Labels.distance(context, e.distanceM),
                    TimeText.duration(e.durationMs)));
        } else {
            second.setText(R.string.track_unreadable);
        }
        return row;
    }
}
