package com.team4.bintime;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/** 아직 통합되지 않은 팀원 담당 탭의 자리 표시용 화면. */
public class PlaceholderFragment extends Fragment {

    public static PlaceholderFragment newInstance(String title, String owner) {
        Bundle args = new Bundle();
        args.putString("title", title);
        args.putString("owner", owner);
        PlaceholderFragment f = new PlaceholderFragment();
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Bundle args = requireArguments();
        TextView t = new TextView(requireContext());
        t.setGravity(Gravity.CENTER);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary));
        t.setText(args.getString("title") + " 화면\n(" + args.getString("owner") + " 담당 · 통합 예정)");
        return t;
    }
}
