import LinkBridgePresentation
import SwiftUI

/// Live transfers pinned at the top, then finished ones grouped by day.
struct HistoryView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let rows = model.rows
        let live = rows.filter { if case .live = $0 { true } else { false } }
        if rows.isEmpty {
            EmptyState(symbol: "clock", message: "Files you send and receive will appear here.")
        } else {
            List {
                if !live.isEmpty {
                    Section("In progress") {
                        ForEach(live) { TransferRow(row: $0) }
                    }
                }
                ForEach(days, id: \.day) { group in
                    Section(group.day.formatted(date: .complete, time: .omitted)) {
                        ForEach(group.records) { TransferRow(row: .finished($0)) }
                    }
                }
            }
        }
    }

    private var days: [(day: Date, records: [TransferRecord])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: model.history) { calendar.startOfDay(for: $0.date) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }
}
