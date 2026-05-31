import { MPCParticipant, MPCProtocol, ModuleResult } from '../../types';
interface ParticipantInput {
    protocolId: string;
    participantId: string;
    encryptedValue: string;
    timestamp: number;
    signature: string;
}
interface MPCResult {
    protocolId: string;
    result: number | boolean | string;
    participants: string[];
    timestamp: number;
    proof: string;
}
export declare class SecureMultipartyComputation {
    private participants;
    private protocols;
    private results;
    constructor();
    registerParticipant(endpoint: string, name?: string): ModuleResult<MPCParticipant>;
    removeParticipant(participantId: string): ModuleResult<boolean>;
    getParticipant(participantId: string): ModuleResult<MPCParticipant | null>;
    listParticipants(): ModuleResult<MPCParticipant[]>;
    createProtocol(name: string, type: MPCProtocol['type'], participantIds: string[], threshold?: number): ModuleResult<MPCProtocol>;
    submitInput(protocolId: string, participantId: string, value: number): ModuleResult<ParticipantInput>;
    private runProtocol;
    getProtocol(protocolId: string): ModuleResult<MPCProtocol | null>;
    listProtocols(): ModuleResult<MPCProtocol[]>;
    getResult(protocolId: string): ModuleResult<MPCResult | null>;
    decryptResult(protocolId: string, participantId: string): ModuleResult<unknown>;
    verifyResult(protocolId: string, proof: string): ModuleResult<boolean>;
    cancelProtocol(protocolId: string): ModuleResult<boolean>;
}
export {};
