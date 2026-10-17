import Foundation

/// State of the Send control. Behaviour is added alongside the model wiring.
enum SendPhase: Equatable {
    case idle
    case ready(fileName: String)
    case sending(fileName: String, progress: Double)
    case sent(fileName: String)
    case failed(fileName: String, message: String)
}
