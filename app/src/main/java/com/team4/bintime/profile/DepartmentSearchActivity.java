package com.team4.bintime.profile;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;
import com.team4.bintime.common.StateView;

import java.util.ArrayList;
import java.util.List;

/**
 * 학과 검색·선택 화면 (Figma 116:2685). (BT-004)
 * '선택'을 누르면 학과명을 돌려주고 닫는다. 뒤로가기는 선택 취소이며 기존 학과를 유지한다.
 */
public class DepartmentSearchActivity extends AppCompatActivity {

    public static final String EXTRA_DEPARTMENT = "department";

    private final List<MockDepartmentRepository.Department> items = new ArrayList<>();
    private RecyclerView.Adapter<RowHolder> adapter;
    private StateView stateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_department_search);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        stateView = findViewById(R.id.departmentState);

        RecyclerView list = findViewById(R.id.departmentList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RowHolder>() {
            @NonNull
            @Override
            public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RowHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_department, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RowHolder holder, int position) {
                MockDepartmentRepository.Department d = items.get(position);
                holder.name.setText(d.name);
                holder.college.setText(d.college);
                holder.select.setOnClickListener(v -> {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_DEPARTMENT, d.name));
                    finish();
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        list.setAdapter(adapter);

        EditText search = findViewById(R.id.etDepartmentSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { render(s.toString()); }
        });
        render(search.getText().toString());
    }

    private void render(String query) {
        items.clear();
        items.addAll(MockDepartmentRepository.search(query));
        adapter.notifyDataSetChanged();
        if (items.isEmpty()) {
            stateView.showEmpty(getString(R.string.department_empty_title), getString(R.string.department_empty_message));
        } else {
            stateView.hide();
        }
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView name, college, select;

        RowHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.departmentName);
            college = itemView.findViewById(R.id.departmentCollege);
            select = itemView.findViewById(R.id.departmentSelect);
        }
    }
}
